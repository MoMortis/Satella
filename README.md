# Satella

双版本（1.21.11 + 26.2）单 jar 客户端模组：自动交易、全自动合成、结构与群系定位器等实用功能合集。

> 本模组的开发与维护使用了 **GPT、DeepSeek、GLM** 三个 AI 模型协作完成。

## 功能概览

### 自动交易

- 配置目标物品后，无需打开村民界面即可后台交易：
  - `/autotrade add [item]`：加入出售物品池（如 `iron_ingot`、`melon`）
  - `/autotrade remove [item]`：移出物品池
  - `/autotrade list`：查看物品池
- 自动模式：右键附近的村民按交易间隔自动购买，GUI 可显示可隐藏，交易期间保持打开
- 单次模式：买完一轮即关闭界面
- 交易目标用村民 UUID 跟踪，退出重进后高亮与状态不丢失
- 可配置：交易间隔、每轮成交次数、掉落产物、刷新交易界面（0~1200 gt）等
- 交易热键在任意界面上下文均可用

### 全自动合成 / 全自动切石

- 依赖 Item Scroller 的批量合成逻辑，扫描背包 → 建材料表 → 按缺口补料 → 循环取出产物
- 材料按数量记账：按缺口补满到最大堆叠，半堆不超过缺口才取
- 残差合成（Ctrl+Alt+C）与全自动合成可合并为独立周期配置
- 「合成残余」配置（0~32）：物品堆数量低于阈值时跳过
- 26.2 版本为独立合成实现（ResidualCrafting），不依赖 Item Scroller

### 物品保护

- 白名单语义拦截目标物品丢弃：手持 Q/Ctrl+Q、容器内 Q/Ctrl+Q、光标移出界面丢弃均被拦截（含创造模式物品栏），残差合成内部 THROW 取产物不受影响

### 更NB的弩

- 连射增强；容器打开或 litematica-printer 快捷潜影盒自动补货期间自动暂停连射，结束后恢复

### /st 定位器

- 纯客户端 `/st` 群系与结构定位（移植 Datapack Map 算法），支持带 lithostitched 的数据包
- `/st anystructure` / `/st anybiome`：就近多结构/多群系查询
- 多环定位：MaLiLib 配置切比雪夫距离环 → 种子 + 数据包子集，附可视化规则编辑器（`/st rules`）
- `/st` 查询结果可输出 Xaero 航点串，点击即导入

## 构建

```powershell
# 1.21.11 主项目（zulu21）
.\gradlew.bat build --no-daemon --console=plain

# 26.2 子项目（zulu25）
$env:JAVA_HOME='D:\PCL2\JDK\zulu25.30.17-ca-jdk25.0.1-win_x64'
.\gradlew.bat -Pbuild26 :v26_2:installEmt --no-daemon --console=plain
```

产物为仓库根目录的双版本嵌套 jar `Satella.jar`。26.2 构建细节见 [MIGRATE_1.21.11_TO_26.2.md](MIGRATE_1.21.11_TO_26.2.md)。

## 更新历史

按 git 提交记录整理的完整更新历史见 [CHANGELOG.md](CHANGELOG.md)。

## License

CC0 license. Feel free to learn from it and incorporate it in your own projects.
