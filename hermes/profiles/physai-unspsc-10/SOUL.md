# physai-unspsc-10 — 生きた動植物・資材（UNSPSC 10）／都市養蜂の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-unspsc-10`、UNSPSC segment 10 生きた動植物とその資材・用品。都市養蜂・送粉サービス事業者）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 巣箱監視ロボット（重量・温度・分蜂の音響兆候の計測、送粉ルートのマッピング）が養蜂場で現地監視を行い、
Apiary Operations Governor が介入を統制する。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:lift-honey-super` | manipulator | 継箱（蜜の入ったスーパー）を育児箱から持ち上げ、巣箱脇の計量台へ置く | 肩関節ピークトルク | 250 N·m（estimate） |
| `:rooftop-super-cart` | transport | 採蜜した継箱 60 kg を台車で屋上 30 m を運び、スロープを下って荷物用 EV へ | 最小転倒余裕 | ≥ 0.3（estimate） |
| `:hive-wall-roof-heat` | thermal | 日射を受ける屋上の 20 mm 杉板巣箱壁（午後 6 時間）。内面は 35 °C の育児圏の空気 | 6 時間後の壁内面温度 | 38 °C（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/apiaryops/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。
repo 自身の `test/` も同じ runner で走る。着地時点で 79 tests / 214 assertions / 0 fail）。

## 測って分かったこと・限界（成長の第一候補）

1. **継箱の持ち上げ**: 肩トルクは 5 kg で 139.4 N·m、10 kg で 179.2 N·m、15 kg で 219.1 N·m、25 kg で 298.9 N·m。
   限界 250 N·m に達する継箱重量は **18.9 kg** —— 蜜で満ちた深型継箱はこのアームでは持てず、枠単位での取り出しが要る。
2. **屋上台車**: 転倒余裕は平地 0.832、8° で 0.599、12° で 0.478、16° で 0.352（ここで駆動力 400 N が律速、40.06 s）、20° では**停止（stall）**。
   境界 **17.14°** は転倒余裕 0.3 ではなく駆動力が尽きる勾配で、余裕が 0.3 を割るより先に登れなくなる。平地〜12° の所要時間は 38.9 s で一定（加速度上限が律速）。
3. **巣箱壁**: 6 時間後の内面温度は外気（sol-air）40 °C で 37.3 °C、45 °C で 39.6 °C（38 °C 到達 1559 s）、55 °C で 44.2 °C（793 s）、75 °C で 53.5 °C（523 s）。
   限界 38 °C を超える外側温度は **41.5 °C**。蜂群自身の扇風・水の蒸散による冷却は solver に無い（内面は対流だけ）ので、これは無対策時の値。
4. **estimate のままの値（成長候補）**:
   - 肩トルク 250 N·m → 採用するアームのデータシート。
   - 転倒余裕 0.3 → 搬送台車の安全規格かメーカー仕様。
   - 内面 38 °C と育児圏 35 °C → 養蜂学の文献（育児圏温度の実測値）を原典で確かめる。
   - 杉板の熱物性（k 0.12、ρ 500、c 1600）、外面の熱伝達係数 15 W/m²K、台車の駆動力・転がり抵抗係数。
   - solver に足りないもの: 日射吸収（sol-air 温度を外気温の代わりに入れている）、蜂群の能動冷却。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-unspsc-10 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-unspsc-10 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
