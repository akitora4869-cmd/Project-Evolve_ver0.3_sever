# Project EVOLVE Server v0.5.2

Paper 1.21.1 / Java 21 server plugin.

## v0.5.2
- Monster normal melee is now a true forward fan/cone hit test.
- Three curved claw traces visualize the same attack direction.
- Evolve-style two-layer Monster durability: Health + Armor (外皮).
- Default Stage growth:
  - Stage 1: HP 100 / Armor 50
  - Stage 2: HP 140 / Armor 80
  - Stage 3: HP 180 / Armor 120
- Damage is absorbed by Armor first; remaining damage reaches Health.
- Feeding restores Armor but does not directly restore lost Health.
- Evolution adds the newly gained max-health capacity and renews Armor.
- Monster sidebar now shows HP / Armor / Evolution.
- All values are configurable in config.yml.

## Test
1. `/evolve monster`
2. `/evolve wildlife 6`
3. Left click to test the cone + claw attack.
4. Let another player hit the Monster to test Armor -> Health damage.
5. Eat wildlife corpses to restore Armor and gain Evolution.
6. `/evolve levelup` can force Stage 2 / 3 for testing.

Build: `mvn -B clean package`
Expected jar: `target/project-evolve-0.5.2.jar`


## v0.5.2 Manual evolution
- Evolution points no longer trigger stage-up automatically.
- When the requirement is met, the Monster can run `/evolve evolve` at any chosen time.
- Evolution takes a configurable amount of time (Stage 2: 8s, Stage 3: 12s by default).
- Once started, evolution cannot be cancelled by the Monster.
- The Monster is rooted and cannot attack or feed while evolving. Looking around remains possible.
- On completion, the new Stage is applied and Armor is set to 0. Health still gains only the new Stage's additional max-health amount.


## v0.5.2
- Monster hotbar is a protected skill deck; inventory movement/drop/offhand swap are blocked.
- Slot 9 is EVOLVE. Right click starts the timed, non-cancellable evolution when READY.
- Evolution no longer roots Monster movement; the earlier wording referred to immovable skill items.
- Monster walk speed: Stage 1 120%, Stage 2 125%, Stage 3 130% (configurable).
- Hunger is disabled for Monster.
- Sidebar defaults OFF; scoreboard teams still synchronize role/stage/evolving state to the Fabric client.
- Hidden vanilla player health/absorption/XP fields are synchronized as HUD transport values.


## 進化中の移動固定
進化スキル開始時の座標を保存し、進化完了までMonster本体をその位置に固定します。WASD、ジャンプ、ノックバック、爆発などでは移動できません。カメラのYaw/Pitchは固定しないため、周囲を自由に見回せます。進化自体は開始後キャンセルできません。
