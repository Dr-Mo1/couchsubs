// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import java.util.Locale

/** Character labels from the Windows Arabic fork; unknown names remain unchanged. */
object ArabicSpeakerNames {
    internal val names = mapOf(
        "Lucia" to "لوسيا",
        "Donnie" to "دوني",
        "Jason" to "جيسون",
        "Brian" to "برايان",
        "Jay" to "جاي",
        "Cal" to "كال",
        "Monty" to "مونتي",
        "Mattia" to "ماتيا",
        "Raul" to "راؤول",
        "Lori" to "لوري",
        "Kat" to "كات",
        "Andrea" to "أندريا",
        "Boobie" to "بوبي",
        "Caminos" to "كامينوس",
        "Andy" to "أندي",
        "Tommy" to "تومي",
        "Omar" to "عمر",
        "Lou" to "لو",
        "Arnold" to "أرنولد",
        "Mia" to "ميا",
        "Polilla" to "بولييا",
        "Charles" to "تشارلز",
        "Andrew" to "أندرو",
        "Dimez" to "دايمز",
        "Dre" to "دري",
        "Valentina" to "فالنتينا",
        "Dobbs" to "دوبس",
        "Kyle" to "كايل",
        "Kenton" to "كينتون",
        "Lucia Caminos" to "لوسيا كامينوس",
        "Felipe" to "فيليبي",
        "Bill" to "بيل",
        "Roxy" to "روكسي",
        "Cisco" to "سيسكو",
        "Mandy" to "ماندي",
        "Pete" to "بيت",
        "Kamal" to "كمال",
        "Gil" to "جيل",
        "Booby" to "بوبي",
        "Mangrove" to "مانجروف",
        "Rox" to "روكس",
        "Raymond" to "ريموند",
        "Karim" to "كريم",
        "Tomas" to "توماس",
        "Rodney" to "رودني",
        "Billy" to "بيلي",
        "Nicole" to "نيكول",
        "Bancroft" to "بانكروفت",
        "Joey" to "جوي",
        "Gary" to "جاري",
        "Nate" to "نيت",
        "Kareem" to "كريم",
        "Dunk" to "دانك",
        "Shirley" to "شيرلي",
        "Mr Mangrove" to "السيد مانجروف",
        "Capri" to "كابري",
        "Bri" to "براي",
        "Tootsie" to "توتسي",
        "Kirk" to "كيرك",
        "Walker" to "ووكر",
        "Kevin" to "كيفن",
        "Maria" to "ماريا",
        "Viper" to "فايبر",
        "Dre'Quan" to "دري كوان",
        "Santino" to "سانتينو",
        "Duval" to "دوفال",
        "Percival" to "برسيفال",
        "Galecki" to "جاليكي",
        "Peggy" to "بيجي",
        "Rhonda" to "روندا",
        "Lorena" to "لورينا",
        "Gates" to "جيتس",
        "Carol" to "كارول",
        "Lucita" to "لوسيتا",
        "Mr Raul" to "السيد راؤول",
        "DWNPLY" to "داونبلاي",
        "Abe" to "آيب",
        "Camino" to "كامينوس",
        "Royce" to "رويس",
        "Don" to "دون",
        "Mendez" to "مينديز",
        "Dr Bill" to "دكتور بيل",
        "D-Man" to "دي مان",
        "Venus" to "فينوس",
        "Donny" to "دوني",
        "Vinnie" to "فيني",
        "Mrs Walker" to "السيدة ووكر",
        "Dook" to "دوك",
        "Pedro" to "بيدرو",
        "Juice" to "جوس",
        "Vicky" to "فيكي",
        "Sam" to "سام",
        "Frankie" to "فرانكي",
        "Fabi" to "فابي",
        "Diego" to "دييجو",
        "Armold" to "أرنولد",
        "Flamingo Six" to "فلامنجو ستة",
        "Duvall" to "دوفال",
        "Gonzalez" to "جونزاليس",
        "Nico" to "نيكو",
        "Park" to "بارك",
        "Clint" to "كلينت",
        "Carl" to "كارل",
        "Stephanie" to "ستيفاني",
        "DeSanta" to "دي سانتا",
        "Malik" to "ماليك",
        "Rivers" to "ريفرز",
        "Looney" to "لوني",
        "Luke" to "لوسيا",
        "Lucy" to "لوسيا",
        "Danny" to "داني",
        "Ricky" to "ريكي",
        "Mario" to "ماريو",
        "Matias" to "ماتياس",
        "Guy" to "جاي",
        "Castro" to "كاسترو",
        "Pilot Pete" to "الطيار بيت",
        "Arugula" to "أروجولا",
        "Cameron" to "كاميرون",
        "Beth" to "بيث",
        "Greg" to "جريج",
        "Doug" to "دوغ",
        "Kingfishers" to "كينغفيشرز",
    ).mapKeys { it.key.lowercase(Locale.ROOT) }

    fun display(name: String): String {
        val key = name.replace(Regex("[’']s$", RegexOption.IGNORE_CASE), "").lowercase(Locale.ROOT)
        return names[key] ?: transliterate(key)
    }

    /** Unknown labels get an approximate phonetic spelling; the source identity is never changed. */
    private fun transliterate(name: String): String {
        if (name.any { it in '\u0600'..'\u06ff' } && name.none { it in 'a'..'z' }) return name
        val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace('ı', 'i')
        val pairs = linkedMapOf("sch" to "ش", "igh" to "اي", "sh" to "ش", "ch" to "تش",
            "th" to "ث", "ph" to "ف", "kh" to "خ", "gh" to "غ", "ck" to "ك", "qu" to "كو",
            "oo" to "و", "ee" to "ي", "ou" to "و", "ow" to "او", "ay" to "اي", "ai" to "اي",
            "ea" to "ي", "ie" to "ي")
        val letters = listOf("ا", "ب", "ك", "د", "ي", "ف", "ج", "ه", "ي", "ج", "ك", "ل", "م",
            "ن", "و", "ب", "ك", "ر", "س", "ت", "و", "ف", "و", "كس", "ي", "ز")
        return plain.split(' ').joinToString(" ") { word ->
            names[word] ?: buildString {
                var i = 0
                while (i < word.length) {
                    val pair = pairs.entries.firstOrNull { word.startsWith(it.key, i) }
                    if (pair != null) { append(pair.value); i += pair.key.length }
                    else {
                        val char = word[i++]
                        if (char in 'a'..'z') append(letters[char - 'a'])
                        else if (char != '\'' && char != '’') append(char)
                    }
                }
            }
        }
    }
}
