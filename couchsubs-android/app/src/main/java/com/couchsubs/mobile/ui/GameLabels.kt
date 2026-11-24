// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.ui

import android.content.Context
import com.couchsubs.mobile.R
import com.couchsubs.mobile.core.GameName

/** Localize built-in titles without changing pack identities or user-entered game names. */
internal fun gameLabel(context: Context, title: String): String {
    val resource = when (GameName.key(title.removeSuffix(" (ek satırlar)"))) {
        GameName.key("Grand Theft Auto VI") -> R.string.game_title_1
        GameName.key("Grand Theft Auto V") -> R.string.game_title_2
        GameName.key("Red Dead Redemption 2") -> R.string.game_title_3
        GameName.key("Ghost of Yōtei") -> R.string.game_title_4
        GameName.key("Marvel's Spider-Man 2") -> R.string.game_title_5
        GameName.key("Death Stranding 2: On the Beach") -> R.string.game_title_6
        GameName.key("Elden Ring") -> R.string.game_title_7
        GameName.key("Elden Ring Nightreign") -> R.string.game_title_8
        GameName.key("Assassin's Creed Shadows") -> R.string.game_title_9
        GameName.key("Call of Duty: Black Ops 7") -> R.string.game_title_10
        GameName.key("Call of Duty: Black Ops 6") -> R.string.game_title_11
        GameName.key("Battlefield 6") -> R.string.game_title_12
        GameName.key("EA Sports FC 26") -> R.string.game_title_13
        GameName.key("Mafia: The Old Country") -> R.string.game_title_14
        GameName.key("The Last of Us Part II Remastered") -> R.string.game_title_15
        GameName.key("God of War Ragnarök") -> R.string.game_title_16
        GameName.key("Cyberpunk 2077") -> R.string.game_title_17
        GameName.key("Hogwarts Legacy") -> R.string.game_title_18
        GameName.key("Resident Evil Requiem") -> R.string.game_title_19
        GameName.key("Silent Hill f") -> R.string.game_title_20
        GameName.key("Metal Gear Solid Delta: Snake Eater") -> R.string.game_title_21
        GameName.key("Borderlands 4") -> R.string.game_title_22
        GameName.key("Kingdom Come: Deliverance II") -> R.string.game_title_23
        GameName.key("Clair Obscur: Expedition 33") -> R.string.game_title_24
        GameName.key("Final Fantasy VII Rebirth") -> R.string.game_title_25
        GameName.key("Stellar Blade") -> R.string.game_title_26
        GameName.key("Black Myth: Wukong") -> R.string.game_title_27
        GameName.key("Baldur's Gate 3") -> R.string.game_title_28
        GameName.key("Horizon Forbidden West") -> R.string.game_title_29
        GameName.key("Alan Wake 2") -> R.string.game_title_30
        GameName.key("Indiana Jones and the Great Circle") -> R.string.game_title_31
        GameName.key("Dying Light: The Beast") -> R.string.game_title_32
        GameName.key("Ghost of Tsushima") -> R.string.game_title_33
        GameName.key("Star Wars Outlaws") -> R.string.game_title_34
        GameName.key("The Witcher 3: Wild Hunt") -> R.string.game_title_35
        GameName.key("Monster Hunter Wilds") -> R.string.game_title_36
        GameName.key("Split Fiction") -> R.string.game_title_37
        GameName.key("Hollow Knight: Silksong") -> R.string.game_title_38
        GameName.key("Marvel's Wolverine") -> R.string.game_title_39
        GameName.key("Assassin's Creed Mirage") -> R.string.game_title_40
        GameName.key("Assassin's Creed Valhalla") -> R.string.game_title_41
        GameName.key("Assassin's Creed Odyssey") -> R.string.game_title_42
        GameName.key("Assassin's Creed Origins") -> R.string.game_title_43
        GameName.key("Call of Duty: Modern Warfare III") -> R.string.game_title_44
        GameName.key("Call of Duty: Modern Warfare II") -> R.string.game_title_45
        GameName.key("Call of Duty: Warzone") -> R.string.game_title_46
        GameName.key("Resident Evil 4") -> R.string.game_title_47
        GameName.key("Resident Evil Village") -> R.string.game_title_48
        GameName.key("Resident Evil 2") -> R.string.game_title_49
        GameName.key("Resident Evil 3") -> R.string.game_title_50
        GameName.key("Resident Evil 7: Biohazard") -> R.string.game_title_51
        GameName.key("Silent Hill 2") -> R.string.game_title_52
        GameName.key("The Last of Us Part I") -> R.string.game_title_53
        GameName.key("God of War") -> R.string.game_title_54
        GameName.key("Call of Duty: Modern Warfare (2019)") -> R.string.game_title_55
        else -> return title
    }
    return context.getString(resource)
}
