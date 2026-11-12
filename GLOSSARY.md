# Kung

Kung is a client-side Fabric mod for Hypixel SkyBlock: helpers, trackers, highlights and overlays that the player switches on and tunes in one settings menu. This glossary fixes the words for that menu and for the kinds of things in it, so a report like "the Enabled switch in a second-level parent is cut off" means one thing.

## The menu

**Menu**:
The settings screen. It shows every category as a column, side by side.
_Avoid_: config screen, GUI, settings page

**Native menu**:
The same settings offered through the optional OneConfig or Mod Menu integration instead of the Kung menu.
_Avoid_: OneConfig menu (OneConfig is only the library behind it)

**Category**:
A column of the menu that groups related features, such as Dungeon or Misc.
_Avoid_: tab, section, page

**Feature**:
One thing Kung does that the player can switch on or off, shown as one row in a category. A feature owns its settings.
_Avoid_: module, function, option

**Master toggle**:
The on/off switch of a feature: a click on the feature's own row.
_Avoid_: main switch, enable setting

**Settings-only feature**:
A feature row without a master toggle. It only opens its settings.
_Avoid_: group feature

**Search box**:
The field at the bottom of the menu that filters features and settings by name.

## Settings

**Setting**:
One row inside an opened feature. It holds one value, runs one action or shows one piece of information.
_Avoid_: option, config entry, property

**Toggle**:
A setting with two states, on and off.
_Avoid_: checkbox, switch

**Slider**:
A setting that picks a number on a track. The track keeps the slider's range; the **value box** beside it shows the number and takes a typed one, also below and above that range (the knob then sits at the nearer end). The box is the way to any number the setting allows, the track one way to move it.
_Avoid_: number field

**Stepper**:
A setting that changes a number with a minus and a plus button.

**Choice**:
A setting that picks one value from a fixed list, by cycling or from a dropdown.
_Avoid_: dropdown, select

**Text field**:
A setting the player types into.

**Keybind**:
A setting that records a key or mouse button.

**Color setting**:
A setting shown as a swatch with its #RRGGBB, which opens the color popup.

**Button**:
A setting that runs an action when clicked.

**Info line**:
A read-only line of text in the settings.
_Avoid_: label (a label is a setting's name)

**Group**:
A setting with no value of its own that only holds child settings.
_Avoid_: folder, header

**Label**:
The name of a setting, drawn at the left of its row.

**Control**:
The part of a row the player clicks, at the right of the row: a switch, a slider track, a swatch or a button.

**Two-line row**:
A setting whose control spans the full row below its label, such as a slider or a text field.

**Reset button**:
A small arrow button before a control that puts the setting back to its default. It shows only while the value differs from the default.

**Developer-only**:
A feature or setting that only the developer's account sees, marked with a small yellow star.

## Nesting

**Parent setting**:
A setting that has child settings and can be expanded. A closed parent shows ">" and an open one shows "v".
_Avoid_: dropdown setting, collapsible

**Child setting**:
A setting listed below a parent setting, indented, visible only while that parent is expanded.
_Avoid_: sub-setting, nested setting

**Expanding**:
Opening a parent setting, by clicking its arrow or right-clicking its row. Opening a parent closes the open one beside it at the same level.
_Avoid_: unfolding, dropping down

**Level**:
How deep a setting sits. The first level is directly inside the feature, the second level is a child, the third level is a child of a child.

**Nested parent**:
A child setting that is itself a parent, so its children sit two levels below the feature.
_Avoid_: double dropdown, sub-sub-setting

**Row toggle**:
A small on/off switch inside a setting's row, left of its control, so it needs no expanding. A hitbox's Enabled switch is one.
_Avoid_: inline toggle (a row toggle is not a Toggle setting: it belongs to its parent row)

## Conditional settings

**Conditional setting**:
A setting that appears or disappears depending on another setting, such as the per-channel toggles that show only while "Use Default Channels" is off.
_Avoid_: dependent setting, dynamic setting, hidden setting

**Greyed-out setting**:
A child setting that stays visible but cannot be used while its parent toggle is off. Only the native menu does this.
_Avoid_: disabled setting (disabled means switched off by the player)

## Hints and help

**Tooltip**:
The text beside a row that explains it while the pointer rests there.
_Avoid_: hover help, hint

**Marquee**:
A cut-off name that slides back and forth while the pointer is on its row, so all of it can be read.
_Avoid_: ticker, scroller

**Editor popup**:
A popup opened from a setting for input that does not fit in a row: the color wheel, the entity search, the text editor.
_Avoid_: dialog, modal

## Kinds of feature

**Helper**:
A feature that guides the player through one game mechanic, such as the Superpairs Helper.

**Tracker**:
A feature that counts or remembers something over time, such as the Sack Tracker.

**Highlight**:
A box, outline or glow drawn in the world around something, such as hitboxes or colored pillars.
_Avoid_: ESP, outline

**Solver**:
A feature that works out the answer to a puzzle, such as the Experiment Solver.

**Auto feature**:
A feature that sends inputs for the player, such as Auto Click or Auto Harp. Full flavor only.
_Avoid_: macro, bot

**HUD element**:
Text or graphics drawn on the screen that the player can move and scale.
_Avoid_: widget, overlay

**HUD editor**:
The screen where the player moves and scales HUD elements.

**Secrets overlay**:
The HUD element that lists every dungeon player's secrets of the current run. The own number is the secret tally: a rise of the party total in the tab that follows an own chest click, skull click, item pickup or bat kill. A teammate's number is what that teammate's own Kung reported. Nobody's number is guessed: the overlay shows "?" instead.
_Avoid_: secret counter

**Best run**:
The fastest finished run Kung recorded on a floor: every split, Boss Entry and Total, saved apart from the personal best of each split. Splits Overlay shows it behind a thin line and the chat paste after " | " when Include PB Run is on. Each row also shows the **delta**: this run's time minus the best run's, colored on a gradient from green (much faster) through yellow to red (much slower), where "much" is a tenth of the best run's time for that row (a running phase shows only once it is slower). A faster finished run replaces the best run and prints "new PB run recorded".
The **PB scope** (child setting Scope of Include PB Run) picks the best run the overlay and the paste compare with: All Time is the saved one, Session is the fastest run since the game started (kept in memory, empty until a run finishes on that floor, never saved). The saved best run and the notice stay all time.
_Avoid_: PB run (the setting's name only)

**Planner**:
A feature with a screen of its own for planning, such as the Greenhouse planner.

**Hologram**:
A semi-transparent 3D preview in the world of what the player should build.

**Slash command**:
A command typed as `/kung ...`.

**Join shortcut**:
A top-level slash command such as `/f7` or `/m1` that starts a Catacombs floor. It has no setting and is not a feature.

**Channel shortcut**:
A top-level slash command such as `/cp` or `/cg` that sends the `/chat` command of one send channel: Party, All, Guild or Co-op. It has no setting and is not a feature.

**Friend suggestions**:
The player's Hypixel friends, added to the tab suggestions wherever a slash command takes a player name: the commands Kung lists, such as `/p`, `/msg` and `/pv`, and any text argument the command tree calls a player. Kung only listens: it learns friends from "Friend >" join and leave lines and from the pages of `/friend list` the player opens, and keeps them per account. They have no setting and are not a feature.

**Chat command**:
A message such as `!c50` that other players type in party chat and Kung answers. `!kung help` answers with the commands that are on in that chat, `!kung` with the installed and the newest GitHub and Modrinth version. `!kung` has no switch. `!ca` takes two optional flags after the name, each at most once and in either order: a class flag (`-arch`) and a floor flag (`-f7`, `-m3`, `-e` or `-entrance`; M7 without one). `!c` and the class level commands (`!t50 -m6`) take the floor flag only. Every command with a player also takes one **profile flag**, the SkyBlock profile's fruit name (`-coconut`, `-mango`), in any order with the other flags; without it the selected profile answers. `!be barb -yrkuna -coconut` puts it behind the player flag; `!be barb -coconut` alone is the profile of the sender, so a player named like a fruit is asked with `-lemon -lemon`. The level has no upper limit; a goal beyond about 10^17 runs answers "infinite". `!be` (also `!bestiary`) takes mob words and an optional player flag instead of a level: `!be barb -yrkuna` answers the Bestiary kills of the mob whose name the words start ("barb" is Barbarian Duke X), for that player. The Kung server finds the mob, reads the kills and writes the answer line; a start that fits several mobs lists the best three instead of guessing.
_Avoid_: bang command

**Chat channel**:
One kind of chat line that Chat Channels can switch off: Guild, Party, Co-op or DMs. Public chat and system lines belong to no channel and are never hidden in All. A hidden channel's lines stay stored, so switching it on brings them all back.

**Channel tab**:
One of the tabs under the open chat: All, Party, Guild, Co-op, DMs. A tab shows only its own chat channel and, except DMs and unless Chat Follows Tab is off, sends `/chat` to switch the send channel. The All tab shows every line except the chat channels switched off in its flyout.
_Avoid_: filter bar, chat tab (a bare "tab" is a menu column)

**Flyout**:
The list of one check per chat channel that opens over the All tab while the pointer is on it.
_Avoid_: dropdown, popup (a popup is an editor popup)

**Send channel**:
The channel Hypixel sends the player's next chat message to: All, Party, Guild, Officer or Co-op. `/chat party` sets it. Current Chat Display shows it; the channel tabs underline it. Kung also reads it from the echo of a plain message of the player, which shows the channel the message landed in; a "To Name: text" echo means a chat conversation with that player is open, even when its notice came before the game started.
_Avoid_: current chat, active channel

## Waypoints

**Waypoint**:
One marked block with a name, a color and an on/off state, drawn in the world.

**Waypoint group**:
The waypoints that share their areas, a draw type and a through-walls choice. Always say "waypoint group": a bare "group" is the Group setting.
_Avoid_: category, folder

**Area**:
An island a waypoint group shows on. A waypoint group with no area ticked shows on every island. The Show All tab of the editor lists every waypoint group with its areas to tick.
_Avoid_: location

**Marked group**:
A waypoint group the player starred in the editor. The Add Waypoint Key puts a new waypoint at the looked-at block into every marked group that shows on the current island.

**Walls**:
The waypoint group choice to show its waypoints through blocks. The wall look says what shows: the Icon, a small square at the waypoint, or the Waypoint itself drawn in its type's look.

**Route**:
A waypoint group whose waypoints show one after the other, with the previous, current and next one colored. Skyblocker calls it an ordered group.
_Avoid_: ordered group, path

**Edit island**:
The island whose waypoint groups the editor shows and the `/kung waypoints` subcommands change. It starts as the island the player is on; the island menu or `/kung waypoints island` picks another.

## Crimson Isle minibosses

**Miniboss**:
One of the five Crimson Isle bosses (Bladesoul, Mage Outlaw, Barbarian Duke X, Ashfang, Magma Boss). Each respawns in its own area after it dies.
_Avoid_: mini boss, boss (a dungeon boss is a floor boss)

**Miniboss beacon**:
The beacon block that stands in a miniboss area for a while after the miniboss died. Its disappearance is expected to narrow when the miniboss respawns.

**Miniboss timer**:
The HUD element, one line per miniboss: the time to its respawn, a range, "Soon!", "Spawned" or "Unknown". The respawn comes 122.7 s after the death and 61.8 s after the beacon goes; the beacon stands 61 s. The death is read from the DOWN message or, without one, from the beacon appearing in view. A beacon seen standing gives a range. No beacon proves nothing, since a lobby joined after the death never shows it, so then the range is the whole wait. "Soon!" shows from the due time until the boss itself or the spawning message proves the spawn; only that proof says "Spawned". It needs the player near: about 80 blocks for the beacon, and at the spawn spot (within 20 blocks) to know that the boss is gone.

## Builds and diagnostics

**Flavor**:
One of the two builds of the same source. The full flavor is the GitHub jar. The Modrinth flavor is the Modrinth jar, without the automation and other cut features.
_Avoid_: edition, variant (a version is the mod's release number or the Minecraft version)

**Config**:
The saved settings of the player, in one file. Each category keeps its own part of it.

**Trace**:
A log file of tagged events that Kung writes so a behavior seen in game can be read back afterwards. Each tag is a trace area.
_Avoid_: debug log

**Dev build**:
A build whose version ends in `-dev`. It is never given to other players.

**Verified**:
Seen working in game by the player. A change that only builds and passes its unit tests is unverified.
