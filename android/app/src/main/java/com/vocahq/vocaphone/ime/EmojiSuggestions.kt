package com.vocahq.vocaphone.ime

/**
 * Emoji offered for a word as it is typed: "lol" offers 😂.
 *
 * A curated table, not a lookup into [EmojiCatalog]. The catalog's keywords
 * exist for a deliberate search in the emoji panel. Matched against ordinary
 * prose they answer "the" with 🤣, "dog" with 💩, and "clock" with 🏫.
 *
 * Exact whole words only. A prefix match would put an emoji on the strip
 * while the user is still two letters into a different word.
 */
internal object EmojiSuggestions {
    const val MINIMUM_LENGTH = 2

    fun glyph(word: String): String? = glyphs(word).firstOrNull()

    fun glyphs(word: String): List<String> {
        val key = word.lowercase()
        if (key.length < MINIMUM_LENGTH) return emptyList()
        val primary = TRIGGERS[key] ?: return emptyList()
        return (listOf(primary) + EXTRAS[key].orEmpty()).distinct()
    }

    internal val TRIGGERS: Map<String, String> = mapOf(
        "lol" to "😂", "lmao" to "🤣", "rofl" to "🤣", "haha" to "😂", "hehe" to "😄",
        "funny" to "😂", "omg" to "😱", "wow" to "😮", "yay" to "🎉", "oops" to "🙈",
        "ugh" to "😩", "meh" to "😐", "cool" to "😎", "nice" to "👍", "awesome" to "🤩",
        "amazing" to "🤩", "perfect" to "👌", "done" to "✅", "agree" to "👍",
        "congrats" to "🎉", "congratulations" to "🎉", "welcome" to "🤗",

        "love" to "❤️", "loved" to "❤️", "heart" to "❤️", "happy" to "😊", "sad" to "😢",
        "cry" to "😭", "crying" to "😭", "angry" to "😠", "mad" to "😠", "tired" to "😴",
        "sleepy" to "😴", "sleep" to "😴", "sick" to "🤒", "scared" to "😱",
        "confused" to "😕", "bored" to "🥱", "excited" to "🤩", "shy" to "😊",
        "hungry" to "😋", "thirsty" to "🥤", "stressed" to "😰", "relieved" to "😌",

        "hi" to "👋", "hello" to "👋", "hey" to "👋", "bye" to "👋", "goodbye" to "👋",
        "thanks" to "🙏", "thankyou" to "🙏", "please" to "🙏", "sorry" to "😔",
        "hug" to "🤗", "hugs" to "🤗", "kiss" to "😘", "wink" to "😉",

        "ok" to "👌", "okay" to "👌", "clap" to "👏", "applause" to "👏", "bravo" to "👏",
        "wave" to "👋", "pray" to "🙏", "prayers" to "🙏", "muscle" to "💪",
        "strong" to "💪", "facepalm" to "🤦", "shrug" to "🤷", "thinking" to "🤔",
        "eyes" to "👀", "brain" to "🧠",

        "fire" to "🔥", "lit" to "🔥", "skull" to "💀", "ghost" to "👻", "alien" to "👽",
        "robot" to "🤖", "poop" to "💩", "party" to "🎉", "birthday" to "🎂",
        "cake" to "🎂", "gift" to "🎁", "present" to "🎁", "balloon" to "🎈",
        "fireworks" to "🎆", "crown" to "👑", "diamond" to "💎", "trophy" to "🏆",
        "winner" to "🏆", "medal" to "🥇", "idea" to "💡", "bug" to "🐛",
        "warning" to "⚠️", "rocket" to "🚀",

        "pizza" to "🍕", "burger" to "🍔", "fries" to "🍟", "coffee" to "☕",
        "tea" to "🍵", "beer" to "🍺", "wine" to "🍷", "water" to "💧",
        "breakfast" to "🥞", "lunch" to "🍽️", "dinner" to "🍽️", "snack" to "🍿",
        "chocolate" to "🍫", "cookie" to "🍪", "icecream" to "🍦", "apple" to "🍎",
        "banana" to "🍌", "biryani" to "🍛", "chai" to "🍵",

        "dog" to "🐶", "puppy" to "🐶", "cat" to "🐱", "kitten" to "🐱", "bird" to "🐦",
        "fish" to "🐟", "tree" to "🌳", "flower" to "🌸", "rose" to "🌹",
        "sun" to "☀️", "sunny" to "☀️", "moon" to "🌙", "star" to "⭐",
        "rain" to "🌧️", "rainy" to "🌧️", "snow" to "❄️", "storm" to "⛈️",
        "rainbow" to "🌈", "beach" to "🏖️",

        "home" to "🏠", "house" to "🏠", "office" to "🏢", "school" to "🏫",
        "hospital" to "🏥", "bank" to "🏦", "gym" to "🏋️", "workout" to "🏋️",
        "running" to "🏃", "walk" to "🚶", "travel" to "✈️", "vacation" to "✈️",
        "holiday" to "✈️", "flight" to "✈️", "plane" to "✈️", "airport" to "✈️",
        "car" to "🚗", "train" to "🚆", "bike" to "🚲", "shopping" to "🛒",
        "money" to "💰", "cash" to "💰", "salary" to "💰",

        "music" to "🎵", "song" to "🎵", "book" to "📚", "reading" to "📚",
        "phone" to "📱", "laptop" to "💻", "computer" to "💻", "email" to "📧",
        "mail" to "📧", "calendar" to "📅", "meeting" to "🗓️", "deadline" to "⏰",
        "clock" to "⏰", "alarm" to "⏰", "camera" to "📷", "photo" to "📷",
        "movie" to "🎬", "game" to "🎮", "gaming" to "🎮", "art" to "🎨",
        "football" to "⚽", "cricket" to "🏏", "basketball" to "🏀",

        "christmas" to "🎄", "halloween" to "🎃", "diwali" to "🪔",
    )

    // A couple of neighbours for the feeling-words people actually tap.
    private val EXTRAS: Map<String, List<String>> = mapOf(
        "sad" to listOf("😭", "😞"),
        "happy" to listOf("😄", "😁"),
        "cry" to listOf("😢"),
        "crying" to listOf("😢"),
        "love" to listOf("💕", "😍"),
        "angry" to listOf("😡"),
        "mad" to listOf("😡"),
        "lol" to listOf("🤣"),
        "skull" to listOf("☠️"),
    )
}
