<img width="1280" height="1280" alt="AutoTrade" src="https://github.com/user-attachments/assets/faf0b6d0-3427-49ca-80b3-26af8b867b56" />

This mod allows you to trade away specified items to villagers with one click without even opening the GUI!

## Setup

* Do "/autotrade add [item]" to add an item to the pool of items you want to sell. Use a valid Minecraft item such as iron_ingot or melon. The mod will let you know if the item exists in the Minecraft registry.
* Do "/autotrade remove [item]" to remove an item from the sell pool.
* Do "/autotrade list" to list all the items in the pool.

## Note

In order to get a clean click that doesn't open the GUI, autotrade tricks the server by sending a packet that does't update the user inventory. This means that your items will not visually update until the player opens a container such as a chest, shulker box, furnace, or another villager. This is 100% safe and is intended behavior.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
