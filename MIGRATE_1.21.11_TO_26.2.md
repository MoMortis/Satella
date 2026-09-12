# Auto Trade: Fabric 1.21.11 to 26.2 Migration Notes

This document is for AI agents continuing the 26.2 port. It records the local environment, the build architecture, naming differences, known pitfalls, and commands that have worked in this workspace.

> 本项目（含本文档）的开发与维护使用了 **GPT、DeepSeek、GLM** 三个 AI 模型协作完成。

## Goal

Maintain two independent Fabric artifacts from one repository:

- `auto-trade-fabric-1.21.11.jar`: the original Yarn-named 1.21.11 build.
- `auto-trade-fabric-26.2.jar`: an independent Mojang-named 26.2 build.

Do not compile the 1.21.11 Yarn source tree for the 26.2 artifact. The two Minecraft versions use incompatible names and APIs.

## Local Environment

Workspace:

```text
D:\PCL2\.minecraft\versions\Mortis\EMT
```

26.2 instance:

```text
D:\PCL2\.minecraft\versions\Petra
```

Verified 26.2 runtime:

```text
Minecraft:     26.2
Fabric Loader: 0.19.3
Java:          25
Fabric API:    0.157.0+26.2
MaLiLib:       0.29.3
Item Scroller: 0.32.1
Mod Menu:      20.0.1
```

Java used for the 26.2 build:

```text
D:\PCL2\JDK\zulu25.30.17-ca-jdk25.0.1-win_x64
```

Relevant local files:

```text
D:\PCL2\.minecraft\versions\Petra\Petra.jar
D:\PCL2\.minecraft\versions\Petra\mods\fabric-api-0.157.0+26.2.jar
D:\PCL2\.minecraft\versions\Petra\mods\malilib-fabric-26.2-0.29.3.jar
D:\PCL2\.minecraft\versions\Petra\mods\[物品滚轮] itemscroller-fabric-26.2-0.32.1.jar
D:\PCL2\.minecraft\versions\Petra\mods\[模组菜单] modmenu-20.0.1.jar
D:\PCL2\.minecraft\libraries\net\fabricmc\fabric-loader\0.19.3\fabric-loader-0.19.3.jar
D:\PCL2\.minecraft\libraries\net\fabricmc\sponge-mixin\0.17.3+mixin.0.8.7\sponge-mixin-0.17.3+mixin.0.8.7.jar
```

## Project Layout

The root project remains the normal Loom/Yarn 1.21.11 project.

```text
build.gradle                 1.21.11 Loom build
src/main/java                1.21.11 Yarn source
src/main/resources           1.21.11 resources
v26_2/build.gradle           independent Java 25 26.2 build
v26_2/src/main/java          independent Mojang-named 26.2 source
v26_2/src/main/resources     independent 26.2 metadata and Mixins
```

`settings.gradle` only includes `v26_2` when `-Pbuild26` is provided. This is intentional: Java 25 and local 26.2 classpath configuration must not affect the 1.21.11 build.

```groovy
if (gradle.startParameter.projectProperties.containsKey('build26')) {
    include 'v26_2'
}
```

## Why Standard Loom Was Not Used for 26.2

The attempted official-Mojang-mappings Loom setup failed with:

```text
Failed to find official mojang mappings for 26.2
```

The working 26.2 build is therefore a direct Java compile against the local Mojang-named `Petra.jar`, local Fabric/MaLiLib/Item Scroller jars, Sponge Mixin, and the local Minecraft libraries tree.

Consequences:

- No remap step is available for the 26.2 jar.
- 26.2 source must use Mojang class and member names as present in `Petra.jar`.
- Every 26.2 Mixin must target Mojang names and use `remap = false` where appropriate.
- All required compile dependencies need to be explicitly present. The direct `Petra.jar` classpath does not supply its transitive libraries automatically.

`v26_2/build.gradle` includes `fileTree('D:/PCL2/.minecraft/libraries', '**/*.jar')` for the required Minecraft runtime libraries such as Gson, Guava, Brigadier, DataFixerUpper, Netty, JSpecify, and FastUtil. This is a local build convenience, not a runtime jar-shading mechanism.

## Build Commands

Build only the 1.21.11 root project:

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

Build 26.2:

```powershell
$env:JAVA_HOME='D:\PCL2\JDK\zulu25.30.17-ca-jdk25.0.1-win_x64'
.\gradlew.bat -Pbuild26 :v26_2:build --no-daemon --console=plain
```

Copy the 26.2 output to the EMT root directory, which is the requested distribution location:

```powershell
$env:JAVA_HOME='D:\PCL2\JDK\zulu25.30.17-ca-jdk25.0.1-win_x64'
.\gradlew.bat -Pbuild26 :v26_2:installEmt --no-daemon --console=plain
```

The result must be:

```text
D:\PCL2\.minecraft\versions\Mortis\EMT\auto-trade-fabric-26.2.jar
```

`installPetra` also exists but should not be used unless the user explicitly asks to deploy into the Petra instance's `mods` directory.

## Important Mapping Differences

Examples observed in the actual 26.2 `Petra.jar`:

| 1.21.11 Yarn | 26.2 Mojang |
| --- | --- |
| `net.minecraft.client.MinecraftClient` | `net.minecraft.client.Minecraft` |
| `net.minecraft.client.network.ClientPlayerInteractionManager` | `net.minecraft.client.multiplayer.MultiPlayerGameMode` |
| `net.minecraft.client.network.ClientPlayNetworkHandler` | `net.minecraft.client.multiplayer.ClientPacketListener` |
| `net.minecraft.entity.passive.VillagerEntity` | `net.minecraft.world.entity.npc.villager.Villager` |
| `net.minecraft.screen.MerchantScreenHandler` | `net.minecraft.world.inventory.MerchantMenu` |
| `net.minecraft.screen.CraftingScreenHandler` | `net.minecraft.world.inventory.CraftingMenu` |
| `net.minecraft.screen.slot.SlotActionType` | `net.minecraft.world.inventory.ContainerInput` |
| `SlotActionType.PICKUP` | `ContainerInput.PICKUP` |
| `SlotActionType.THROW` | `ContainerInput.THROW` |
| `net.minecraft.network.packet.s2c.play.SetTradeOffersS2CPacket` | `net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket` |
| `net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket` | `net.minecraft.network.protocol.game.ServerboundSelectTradePacket` |
| `net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket` | `net.minecraft.network.protocol.game.ServerboundContainerClosePacket` |
| `net.minecraft.village.TradeOffer` | `net.minecraft.world.item.trading.MerchantOffer` |
| `net.minecraft.village.TradedItem` | `net.minecraft.world.item.trading.ItemCost` |
| `net.minecraft.registry.Registries` | `net.minecraft.core.registries.BuiltInRegistries` |
| `net.minecraft.util.Identifier` | `net.minecraft.resources.Identifier` |
| `net.minecraft.text.Text` | `net.minecraft.network.chat.Component` |
| `net.minecraft.util.Formatting` | `net.minecraft.ChatFormatting` |

Useful 26.2 calls:

```java
Minecraft minecraft = Minecraft.getInstance();
minecraft.gameMode.interact(player, entity, new EntityHitResult(entity), InteractionHand.MAIN_HAND);
minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
minecraft.gameMode.handleContainerInput(containerId, slot, button, ContainerInput.PICKUP, player);
connection.getConnection().send(new ServerboundSelectTradePacket(offerIndex));
```

## Mixin Rules and Known Crash

The 26.2 `Petra.jar` is already Mojang-named at runtime. Do not rely on Yarn member names or automatic remapping.

Example pattern:

```java
@Mixin(value = Minecraft.class, remap = false)
public class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void autoTrade$tick(CallbackInfo ci) {
        // ...
    }
}
```

Do not use an accessor for `Minecraft.screen`. It does not exist in the 26.2 `Minecraft` class and caused this startup crash:

```text
InvalidAccessorException: No candidates were found matching screen:
Lnet/minecraft/client/gui/screens/Screen; in net/minecraft/client/Minecraft
```

In 26.2 the current screen is owned by `Minecraft.gui`. For Item Scroller integration, use MaLiLib's public helper instead:

```java
GuiUtils.getCurrentScreen()
```

The 26.2 residual-crafting Mixin uses that helper. Do not reintroduce `MinecraftAccessor` unless the exact runtime field is confirmed with `javap`.

## Item Scroller 26.2 Integration

Verified Item Scroller classes and signatures:

```text
fi.dy.masa.itemscroller.event.KeybindCallbacks
  protected int massCraftTicker
  private void onClientTickMassCraftImpl(net.minecraft.client.Minecraft)

fi.dy.masa.itemscroller.recipes.CraftingHandler
  static Slot getFirstCraftingOutputSlotForGui(AbstractContainerScreen<?> gui)
  static CraftingHandler.SlotRange getCraftingGridSlots(AbstractContainerScreen<?> gui, Slot output)

fi.dy.masa.itemscroller.recipes.RecipeStorage
  static RecipeStorage getInstance()
  RecipePattern getSelectedRecipe()

fi.dy.masa.itemscroller.recipes.RecipePattern
  ItemStack getResult()
  ItemStack[] getRecipeItems()
```

The 26.2 residual Mixin is:

```text
v26_2/src/main/java/greenebolt/autotrade/mixin/itemscroller/KeybindCallbacksMixin.java
```

It only cancels Item Scroller's mass-craft method when both are true:

1. `RESIDUAL_CRAFTING` is enabled.
2. `Ctrl+Alt+C` is held.

Normal Item Scroller mass-crafting should remain untouched when residual crafting is disabled or the modified key combination is not held.

Residual behavior currently attempts to leave one item in each eligible source stack, places one material in each required crafting-grid slot, and throws the output using `ContainerInput.THROW`.

## Hidden Automatic Crafting

`AutoCraftController` implements the 26.2 automatic crafting loop:

1. Requires `RESIDUAL_CRAFTING` and `AUTO_CRAFTING`.
2. Searches for a crafting table within 4.5 blocks.
3. Calls `MultiPlayerGameMode.useItemOn()` to open it.
4. `ScreenOpenMixin` intercepts a `MenuType.CRAFTING` open packet and creates the local `CraftingMenu` without showing the GUI.
5. Each two ticks, `ResidualCrafting` runs against slots 1 through 9 and throws the result from slot 0.

This is compile-verified but needs practical in-game regression testing for packet timing, inventory-full behavior, recipe shape edge cases, and server-side anti-cheat behavior.

## MaLiLib Config Registration

The MaLiLib 26.2 source branch is:

```text
https://github.com/sakura-ryoko/malilib/tree/26.2
```

The correct registry API is:

```java
Registry.CONFIG_SCREEN.registerConfigScreenFactory(
    new ModInfo(AutoTrade.MOD_ID, "Auto Trade", AutoTradeConfigGui::new)
);
```

This is executed in `AutoTrade.onInitialize()` after config registration. It makes `Auto Trade` appear in MaLiLib's config switcher.

The 26.2 GUI class is:

```text
v26_2/src/main/java/greenebolt/autotrade/gui/AutoTradeConfigGui.java
```

It extends `GuiConfigsBase` and returns `AutoTradeConfigs.Trade.OPTIONS`.

## Mod Menu Config Entry

The `fabric.mod.json` in `v26_2/src/main/resources` must include:

```json
"entrypoints": {
  "main": ["greenebolt.autotrade.AutoTrade"],
  "modmenu": ["greenebolt.autotrade.compat.ModMenuIntegration"]
}
```

The integration must implement `com.terraformersmc.modmenu.api.ModMenuApi` and return an `AutoTradeConfigGui` with the Mod Menu parent assigned:

```java
@Override
public ConfigScreenFactory<?> getModConfigScreenFactory() {
    return parent -> new AutoTradeConfigGui().setParent(parent);
}
```

The Mod Menu jar is only a `compileOnly` dependency. Do not bundle it into Auto Trade.

## Validation Checklist

Before declaring the 26.2 port complete:

1. Run `:v26_2:build` with Java 25.
2. Confirm `auto-trade-fabric-26.2.jar` exists in the EMT root and not accidentally only in `Petra/mods`.
3. Start Minecraft 26.2 with exactly one Auto Trade jar in the test `mods` folder.
4. Confirm startup has no Mixin apply error.
5. Open Mod Menu and confirm Auto Trade has a working config button.
6. Open a MaLiLib config GUI and confirm `Auto Trade` appears in the mod-switch dropdown.
7. Test manual and automatic villager trading.
8. Test normal Item Scroller mass crafting to ensure it was not overridden unintentionally.
9. Test `Ctrl+Alt+C` residual crafting with stacks of more than one item.
10. Test hidden automatic crafting near a crafting table.

## Useful Inspection Commands

Inspect actual Mojang names in the local game jar:

```powershell
& 'D:\PCL2\JDK\zulu25.30.17-ca-jdk25.0.1-win_x64\bin\javap.exe' `
  -private `
  -classpath 'D:\PCL2\.minecraft\versions\Petra\Petra.jar' `
  net.minecraft.client.Minecraft `
  net.minecraft.client.multiplayer.MultiPlayerGameMode `
  net.minecraft.world.inventory.CraftingMenu
```

Inspect Item Scroller's actual 26.2 signatures:

```powershell
& 'D:\PCL2\JDK\zulu25.30.17-ca-jdk25.0.1-win_x64\bin\javap.exe' `
  -private `
  -classpath 'D:\PCL2\.minecraft\versions\Petra\mods\[物品滚轮] itemscroller-fabric-26.2-0.32.1.jar;D:\PCL2\.minecraft\versions\Petra\Petra.jar' `
  fi.dy.masa.itemscroller.event.KeybindCallbacks
```

Do not infer class names from old Yarn source. Use `javap` against the target instance before writing a Mixin target or injection descriptor.
