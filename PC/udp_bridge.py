#!/usr/bin/env python3
"""
AprilTag FBT - PC UDP Bridge
收 Android 端 UDP 6DoF 数据 → EMA/SLERP 平滑 → 输出 JSON（供 SteamVR 驱动读取）

协议（UDP, Little-Endian）：
  [0]      magic  = 0xAA
  [1]      type   = 0x02（批量）
  [2-5]    poseCount (int32)
  [6+]      poses，每个 36 bytes：
            tagId     (int32)
            tx,ty,tz (float32×3)
            qx,qy,qz,qw (float32×4)
            confidence (float32)

平滑参数（对应原项目）：
  smoothing_factor  = 0.15  # EMA α（原项目 smoothingFactor）
  additional_smooth = 0.80  # 旋转 SLERP 因子（原项目 additionalSmoothing）
  depth_smoothing = 0.10  # 深度平滑阈值（原项目 depthSmoothing）

输出：
  poses.json — 平滑后的 6DoF，SteamVR 驱动读取

依赖：
  pip install numpy
  # 可选：pip install scipy（更好的 SLERP）
"""

import socket
import struct
import time
import json
import numpy as np
from   collections import deque
from   typing import Dict, List, Optional, Tuple

# ── 配置 ──────────────────────────────────────
LISTEN_PORT     = 4242
SMOOTHING_FACTOR = 0.15   # EMA α
ADDTIONAL_SMOOTH = 0.80   # 旋转 SLERP 因子
DEPTH_SMOOTHING = 0.10   # 深度平滑阈值
EMA_WINDOW       = 15     # 最多缓存帧数
OUTPUT_JSON      = "poses.json"
FPS               = 60

# ── 数据结构 ────────────────────────────────────
class SmoothedPose:
    """EMA 位置平滑 + SLERP 旋转平滑"""

    def __init__(self):
        self.pos_history: deque = deque(maxlen=EMA_WINDOW)
        self.rot_history: deque = deque(maxlen=EMA_WINDOW)
        self.last_pos: Optional[np.ndarray] = None
        self.last_rot: Optional[np.ndarray] = None  # [qw,qx,qy,qz]
        self.confidence = 0.0

    def update(self, tx: float, ty: float, tz: float,
                 qx: float, qy: float, qz: float, qw: float,
                 confidence: float) -> List[float]:
        """输入新检测，输出平滑后的 [tx,ty,tz, qx,qy,qz,qw]"""
        new_pos = np.array([tx, ty, tz])
        new_rot = np.array([qw, qx, qy, qz])  # [qw,qx,qy,qz]

        # ── EMA 位置平滑 ──────────────────────
        if self.last_pos is None:
            smooth_pos = new_pos
        else:
            # 深度平滑（对应原项目 depthSmoothing）
            if DEPTH_SMOOTHING > 0 and self.last_pos is not None:
                dist_old = np.linalg.norm(self.last_pos)
                dist_new = np.linalg.norm(new_pos)
                if dist_old > 0:
                    alpha_d = min(1.0, abs(dist_new - dist_old) / (DEPTH_SMOOTHING * dist_old + 0.001))
                    alpha_d = alpha_d ** 2  # 非线性
                    smooth_dist = (1 - alpha_d) * dist_old + alpha_d * dist_new
                    new_pos = new_pos / (dist_new + 1e-6) * smooth_dist

            smooth_pos = SMOOTHING_FACTOR * new_pos + (1 - SMOOTHING_FACTOR) * self.last_pos

        # ── SLERP 旋转平滑 ───────────────────
        if self.last_rot is None:
            smooth_rot = new_rot
        else:
            # 确保四元数最短路径（q 和 -q 是同一个旋转）
            dot = np.dot(new_rot, self.last_rot)
            if dot < 0:
                new_rot = -new_rot
                dot = -dot

            # SLERP
            angle = np.arccos(np.clip(dot, -1, 1))
            if angle < 1e-6:
                smooth_rot = new_rot
            else:
                s0 = np.sin((1 - ADDTIONAL_SMOOTH) * angle) / np.sin(angle)
                s1 = np.sin(ADDTIONAL_SMOOTH * angle) / np.sin(angle)
                smooth_rot = s0 * self.last_rot + s1 * new_rot
                smooth_rot = smooth_rot / np.linalg.norm(smooth_rot)  # 归一化

        self.last_pos = smooth_pos
        self.last_rot = smooth_rot
        self.confidence = confidence

        return [
            smooth_pos[0], smooth_pos[1], smooth_pos[2],
            smooth_rot[1], smooth_rot[2], smooth_rot[3], smooth_rot[0]  # [qx,qy,qz,qw]
        ]


class Bridge:
    def __init__(self, tracker_names=("waist", "left_foot", "right_foot")):
        self.tracker_names = tracker_names
        self.poses: Dict[int, SmoothedPose] = {
            i: SmoothedPose() for i in range(len(tracker_names))
        }
        self.last_update = time.time()

    def parse_packet(self, data: bytes) -> List[Tuple[int, List[float], float]]:
        """解析 UDP 数据包"""
        if len(data) < 6:
            return []
        if data[0] != 0xAA or data[1] != 0x02:
            return []
        n_poses = struct.unpack_from('<i', data, 2)[0]
        results = []
        off = 6
        for _ in range(n_poses):
            if off + 36 > len(data):
                break
            tag_id    = struct.unpack_from('<i',  data, off)[0]
            tx        = struct.unpack_from('<f',  data, off + 4)[0]
            ty        = struct.unpack_from('<f',  data, off + 8)[0]
            tz        = struct.unpack_from('<f',  data, off + 12)[0]
            qx        = struct.unpack_from('<f',  data, off + 16)[0]
            qy        = struct.unpack_from('<f',  data, off + 20)[0]
            qz        = struct.unpack_from('<f',  data, off + 24)[0]
            qw        = struct.unpack_from('<f',  data, off + 28)[0]
            confidence = struct.unpack_from('<f',  data, off + 32)[0]
            results.append((tag_id, [tx, ty, tz, qx, qy, qz, qw], confidence))
            off += 36
        return results

    def update(self, tag_id: int, raw_pose: List[float], confidence: float):
        """更新并返回平滑后的 pose"""
        if tag_id not in self.poses:
            print(f"[WARN] Unknown tag_id: {tag_id}")
            return None
        smoothed = self.poses[tag_id].update(*raw_pose, confidence)
        return smoothed

    def write_json(self, output_path: str):
        """把平滑后的 poses 写成 JSON（SteamVR 驱动读取）"""
        data = {"timestamp": time.time(), "trackers": {}}
        for idx, name in enumerate(self.tracker_names):
            if self.poses[idx].last_pos is not None:
                p = self.poses[idx]
                data["trackers"][name] = {
                    "tx": float(p.last_pos[0]),
                    "ty": float(p.last_pos[1]),
                    "tz": float(p.last_pos[2]),
                    "qx": float(p.last_rot[1]),
                    "qy": float(p.last_rot[2]),
                    "qz": float(p.last_rot[3]),
                    "qw": float(p.last_rot[0]),
                    "confidence": float(p.confidence),
                }
        with open(output_path, 'w') as f:
            json.dump(data, f, indent=2)


# ── 主循环 ──────────────────────────────────────
def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('', LISTEN_PORT))
    sock.settimeout(0.01)
    print(f"[Bridge] Listening on port {LISTEN_PORT}")

    bridge = Bridge()
    last_json_write = time.time()

    while True:
        try:
            data, addr = sock.recvfrom(65535)
            poses = bridge.parse_packet(data)
            for tag_id, raw_pose, conf in poses:
                smoothed = bridge.update(tag_id, raw_pose, conf)
                if smoothed:
                    name = bridge.tracker_names[tag_id] if tag_id < len(bridge.tracker_names) else f"tracker_{tag_id}"
                    print(f"[{name}] t=({smoothed[0]:.3f}, {smoothed[1]:.3f}, {smoothed[2]:.3f})")

        except socket.timeout:
            pass
        except Exception as e:
            print(f"[ERR] {e}")

        # 定期写 JSON（60 FPS → 每 16ms 写一次）
        now = time.time()
        if now - last_json_write > 1.0 / FPS:
            bridge.write_json(OUTPUT_JSON)
            last_json_write = now

        time.sleep(1.0 / (FPS * 2))  # 降低 CPU


if __name__ == '__main__':
    main()
