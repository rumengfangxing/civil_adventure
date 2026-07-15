# Changelog

## 1.3.0 — 2026-07-15

### ✨ 新增

- 冷却批处理：连续方块变化合并重算（`AdventureScoreService.markDirty` + `tickCooldown`）
- 区块加载自动扫描（`ChunkEvent.Load` → `recalculateFullChunk`）
- 文明分类 HIGH → ZONE 覆写（`CivilRegionClassifierMixin`）
- API 扩展：`getRawScoreAt`、`getZoneCenter`

### 🔧 配置

新增`recalc_cooldown_ticks`
