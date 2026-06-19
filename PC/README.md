# AprilTag FBT - PC Bridge

PC 端 Python 脚本，接收 Android 端发来的 6DoF 数据，做 EMA/SLERP 平滑，输出给 SteamVR 驱动。

## 文件说明

| 文件 | 作用 |
|---|---|
| `udp_bridge.py` | 收 UDP → EMA/SLERP 平滑 → 写 `poses.json` |
| `bridge_driver.py` | （占位）SteamVR C++ 驱动接口 |

## 依赖

```bash
pip install numpy
# 可选：更好的 SLERP
pip install scipy
```

## 用法

```bash
# 1. 手机端 AprilTagFBT-Android 启动追踪，设置 PC IP + 端口 4242
# 2. PC 端运行：
python udp_bridge.py

# 3. 输出的 poses.json 被 SteamVR 驱动读取
#    （需要配合 C++ SteamVR 驱动实现）
```

## 协议（UDP）

格式（Little-Endian）：
```
[0]      magic  = 0xAA
[1]      type   = 0x02 (批量)
[2-5]    poseCount (int32)
[6+N×36] poseData (36 bytes each)
```

单个 pose（36 bytes）：
```
tagId     (int32)
tx,ty,tz (float32×3)
qx,qy,qz,qw (float32×4)
confidence (float32)
```

## 平滑参数

在 `udp_bridge.py` 头部修改：
```python
SMOOTHING_FACTOR = 0.15   # EMA α，越小越平滑
ADDITIONAL_SMOOTH = 0.80  # 旋转 SLERP 因子
DEPTH_SMOOTHING  = 0.10  # 深度平滑阈值
```
