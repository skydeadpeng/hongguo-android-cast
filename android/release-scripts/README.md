# 0.12.42 发版辅助脚本（留档）

这些脚本来自已删除的安卓 worktree `/private/tmp/hgandroid`（分支 `codex/android-0.12.8`，
2026-11-11 随统一仓库清理时收进 main 留档）。它们是 0.12.42 发版时的一次性脚本，
**不能再直接运行**：

- 硬编码 `cd /tmp/hgandroid`（worktree 已移除）
- push 目标 `publish codex/android-0.12.8`（分支已删除，安卓线已并入 main）
- 版本号固定为 0.12.42

## 文件

| 文件 | 用途 |
|---|---|
| `bump.sh` | 提交 0.12.42/versionCode 65 版本号 |
| `feed-commits.sh` | 四个 feed 提交（phone/tv × versioned/latest）并推送 |
| `main-sync.sh` | 把发版分支快进到 publish 仓库 main，让设备更新检查可见 |
| `feed-push.sh` | 推送发版分支使 tag 指向正确树 |
| `push-helper.sh` | 推送模拟器验证过的会话恢复修复 |
| `commit-helper.sh` | 0.12.42 两次修复提交（core-js 恢复 + 后台回收会话恢复），含根因说明 |
| `release-notes.md` | 0.12.42 对外更新说明与 APK SHA256 |

流程要点（0.12.42 实践）：先 release 后 feed，feed 必须落 publish 仓库 main 分支，
否则手机收不到更新（0.12.42 曾漏推）。后续发版流程见各版 release 提交。
