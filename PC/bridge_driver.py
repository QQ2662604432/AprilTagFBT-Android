#!/usr/bin/env python3
"""
AprilTag FBT - PC Bridge Driver
接收 Android 端发来的 6DoF 数据，做 EMA/SLERP 平滑，
注入 SteamVR 虚拟 tracker。

协议（UDP，Little-Endian）：
  [0]      magic  = 0xAA
  [1]      type   = 0x02（批量）
  [2-5]    poseCount (int32)
  [6+]      poses，每个 36 bytes：
            tagId     (int32)
            tx,ty,tz (float32×3)
            qx,qy,qz,qw (float32×4)
            confidence (float32)

依赖：
  pip install openvr numpy scipy
"""

import socket
import struct
import time
import numpy as np
from   dataclasses import dataclass, field
from   collections import deque
from   typing import Option, Dict

# ── 配置 ──────────────────────────────────────────────
LISTEN_PORT   = 4242
SMOOTHING_FACTOR = 0.15    # EMA α，越小越平滑（原项目 smoothingFactor）
ADDTIONAL_SMOOTH  = 0.8     # 旋转 SLERP 因子（原项目 additionalSmoothing）
FPS                 = 60
EMA window         = 10      # 最多缓存 10 帧历史

# ── 数据结构 ──────────────────────────────────────────
@dataclass
class SmoothedPose:
    """带 EMA/SLERP 平滑的位姿状态"""
    pos_alpha: float = SMOOTHING_FACTOR
    rot_alpha: float = ADDITIONAL_SMOOTH

    _pos_hist:  deque = field(default_factory=lambda: deque(maxlen=EMA_WINDOW))
    _rot_hist:  deque = field(default_factory=lambda: deque(maxlen=EMA_WINDOW))
    _last_pos: np.ndarray = field(default=None)
    _last_rot: np.ndarray = field(default=None)  # quaternion [qx,qy,qz,qw]

    def update(self, tx: float, ty: float, tz: float,
                 qx: float, qy: float, qz: float, qw: float) -> np.ndarray:
        """输入新检测，输出平滑后的 [tx,ty,tz, qx,qy,qz,qw]"""
        new_pos = np.array([tx, ty, tz])
        new_rot = np.array([qw, qx, qy, qz])  # [qw,qx,qy,qz]（scipy 顺序）

        # EMA 位置平滑
        if self._last_pos is None:
            smooth_pos = new_pos
        else:
            smooth_pos = self.pos_alpha * new_pos + (1 - self.pos_alpha) * self._last_pos

        # SLERP 旋转平滑
        if self._last_rot is None:
            smooth_rot = new_rot
        else:
            # 用 scipy 的 SLERP（四元数球形插值）
            try:
                from scipy.spatial.transform import Rotation
                r_new = Rotation.from_quat(new_rot[[1,2,3,0]])  # [qx,qy,qz,qw] → scipy 顺序
                r_old = Rotation.from_quat(self._last_rot[[1,2,3,0]])
                # 简化 SLERP：加权平均四元数（同向）
                dot = np.clip(np.dot(r_new.as_quat()[[1,2,3,0]], r_old.as_quat()[[1,2,3,0]]), -1, 1)
                if dot < 0:
                    r_new = Rotation.from_quat(-r_new.as_quat()[[1,2,3,0]])  # 确保最短路径
                # 真实 SLERP
                r_smooth = Rotation.slerp(r_new, r_old, self.rot_alpha)
                smooth_rot = np.array([r_smooth.as_quat()[3],  # qw
                                       r_smooth.as_quat()[0],  # qx
                                       r_smooth.as_quat()[1],  # qy
                                       r_smooth.as_quat()[2]]) # qz
            except ImportError:
                # scipy 不可用，用简单加权平均
                smooth_rot = self.rot_alpha * new_rot + (1 - self.rot_alpha) * self._last_rot

        self._last_pos = smooth_pos
        self._last_rot = smooth_rot
        return np.concatenate([smooth_pos, smooth_rot[[1,2,3,0]]])  # [tx,ty,tz, qx,qy,qz,qw]


# ── SteamVR 驱动（简化版，用 openvr 库）────────────
try:
    import openvr
    HAS_OPENVR = True
except ImportError:
    HAS_OPENVR = False
    print("[WARN] openvr not installed, will print poses only")


class SteamVRBridge:
    def __init__(self, tracker_names=("waist", "left_foot", "right_foot")):
        self.tracker_names = tracker_names
        self.poses: Dict[int, SmoothedPose] = {}
        for i in range(len(tracker_names)):
            self.poses[i] = SmoothedPose()
        if HAS_OPENVR:
            self.vr = openvr.OVR()
            # TODO: 真正注入 SteamVR 需要实现 IVRDriver 接口
            # 这里先用 print 占位
            print(f"[SteamVR] Initialized, trackers: {tracker_names}")
        else:
            self.vr = None

    def update_tracker(self, idx: int, pose6: np.ndarray):
        """更新 tracker 位姿（已平滑）"""
        if idx not in self.poses:
            print(f"[WARN] Unknown tracker idx: {idx}")
            return
        smoothed = self.poses[idx].update(*pose6)
        if self.vr:
            # TODO: 调用 SteamVR 驱动注入接口
            # self.vr.update_tracker(idx, smoothed)
            pass
        print(f"[{self.tracker_names[idx]}] "
              f"t=({smoothed[0]:.3f}, {smoothed[1]:.3f}, {smoothed[2]:.3f})")


# ── 主循环 ──────────────────────────────────────────────
def parse_packet(data: bytes) -> list:
    """解析 UDP 数据包，返回 [(tag_id, [tx,ty,tz, qx,qy,qz,qw]), ...]"""
    if len(data) < 6:
        return []
    magic = data[0]
    typ   = data[1]
    if magic != 0xAA or typ != 0x02:
        return []
    n_poses = int.from_bytes(data[2:6], 'little', signed=True)
    results = []
    off = 6
    for _ in range(n_poses):
        if off + 36 > len(data):
            break
        tag_id = int.from_bytes(data[off:off+4], 'little', signed=True)
        tx     = struct.unpack('<f', data[off+4:  off+8])[0]
        ty     = struct.unpack('<f', data[off+8:  off+12])[0]
        tz     = struct.unpack('<f', data[off+12:off+16])[0]
        qx     = struct.unpack('<f', data[off+16:off+20])[0]
        qy     = struct.unpack('<f', data[off+20:off+24])[0]
        qz     = struct.unpack('<f', data[off+24:off+28])[0]
        qw     = struct.unpack('<f', data[off+28:off+32])[0]
        conf   = struct.unpack('<f', data[off+32:off+36])[0]
        results.append((tag_id, [tx,ty,tz, qx,qy,qz,qw], conf))
        off += 36
    return results


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('', LISTEN_PORT))
    sock.settimeout(0.01)
    print(f"[Bridge] Listening on port {LISTEN_PORT}")

    bridge = SteamVRBridge()

    while True:
        try:
            data, addr = sock.recvfrom(65535)
            poses = parse_packet(data)
            for tag_id, pose6, conf in poses:
                # tag_id 对应 tracker 索引（文档：0=waist, 1=left, 2=right）
                idx = tag_id  # 简化：假设 tag_id == tracker_idx
                bridge.update_tracker(idx, np.array(pose6))
        except socket.timeout:
            pass
        except Exception as e:
            print(f"[ERR] {e}")
        time.sleep(1.0 / FPS)


if __name__ == '__main__':
    main()
