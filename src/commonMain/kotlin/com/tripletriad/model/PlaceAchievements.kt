package com.tripletriad.model

/**
 * The places of the client's `zones.json`, as far as an achievement needs them: who has to be
 * beaten there, and which ladder is its tournament.
 *
 * ### A copy, and what keeps it honest
 *
 * `:core` cannot read `zones.json` — the map lives in the client, where the roster is shown — and
 * the server credits achievements without it. So [members] is a **copy**: the opponents of each
 * place that are always there and wait on no badge, the ones the client's `ZoneCatalog.blocks`
 * says hold the way shut. The client's `ZoneBundleTest` re-derives every list from `zones.json` and
 * `npcs.json` and fails when they disagree — the same arrangement as the tribe lists and
 * `CardBundleTest`. Change the map, run that test, change this.
 *
 * ### What each place pays
 *
 * [Place.mgp] is the entry fee of the place's own tournament: clearing a place is what opens its
 * ladder (`Campaign.requiresAchievement` names the achievement here), and the first entry is on
 * the house. It is a copy of `campaigns.json`'s fee for the same reason as above, and
 * `CampaignBundleTest` holds the two together.
 */
internal object PlaceAchievements {
    /**
     * @property members the opponents who must each be beaten once, in `zones.json` order.
     */
    class Place(
        val zoneId: String,
        val campaignKey: String,
        val mgp: Int,
        val members: List<String>,
    )

    val PLACES: List<Place> = listOf(
        Place(
            zoneId = "gold-saucer",
            campaignKey = "gs",
            mgp = 500,
            members = listOf(
                "tt-master",
                "jonas",
                "ruhtwyda",
                "aurifort",
                "guhtwint",
                "queen-of-cards",
            ),
        ),
        Place(
            zoneId = "uldah",
            campaignKey = "uldah",
            mgp = 600,
            members = listOf(
                "roger",
                "kilfufu",
                "wymond",
                "droyn",
                "fufulupa",
                "momodi",
                "fhobhas",
                "hab",
                "papalymo",
            ),
        ),
        Place(
            zoneId = "limsa",
            campaignKey = "limsa",
            mgp = 500,
            members = listOf(
                "memeroon",
                "baderon",
                "mimidoa",
                "gegeruju",
                "furtive-former-imperial",
            ),
        ),
        Place(
            zoneId = "gridania",
            campaignKey = "gridania",
            mgp = 400,
            members = listOf(
                "maisenta",
                "ylaire",
                "miounne",
                "sezul-totoloc",
                "buscarron",
                "momo",
                "landenel",
            ),
        ),
        Place(
            zoneId = "mor-dhona",
            campaignKey = "mor-dhona",
            mgp = 800,
            members = listOf(
                "prudence",
                "tataru",
                "rowena",
            ),
        ),
        Place(
            zoneId = "battlehall",
            campaignKey = "battlehall",
            mgp = 800,
            members = listOf(
                "wyra",
                "nell",
                "prideful-stag",
                "flichoirel",
                "malevolent-weasel",
                "elmer",
                "hall-overseer",
            ),
        ),
        Place(
            zoneId = "ishgard",
            campaignKey = "ishgard",
            mgp = 300,
            members = listOf(
                "joellaut",
                "ourdilic",
            ),
        ),
        Place(
            zoneId = "dravania",
            campaignKey = "dravania",
            mgp = 800,
            members = listOf(
                "redbill-storeboy",
                "seika",
                "mero-roggo",
                "vath-deftarm",
                "mogmill",
                "midnight-dew",
            ),
        ),
        Place(
            zoneId = "gyr-abania",
            campaignKey = "gyr-abania",
            mgp = 700,
            members = listOf(
                "ercanbald",
                "sladkey",
                "arsieu",
                "umber-torrent",
                "garima",
                "ironworks-hand",
                "imperial-deserter",
            ),
        ),
        Place(
            zoneId = "kugane",
            campaignKey = "kugane",
            mgp = 500,
            members = listOf(
                "tokimori",
                "hanagasa",
                "hetsukaze",
                "kotokaze",
                "botan",
                "hokushin",
                "kikimo",
            ),
        ),
        Place(
            zoneId = "othard",
            campaignKey = "othard",
            mgp = 900,
            members = listOf(
                "ushiogi",
                "masatsuchi",
                "gyoei",
                "yusui",
                "tsuzura",
                "kaizan",
                "isobe",
                "munglig",
                "ogodei",
                "nigen",
                "kiuka",
            ),
        ),
        Place(
            zoneId = "norvrandt",
            campaignKey = "norvrandt",
            mgp = 500,
            members = listOf(
                "cobleva",
                "lewto-sue",
                "eo-sigun",
                "redard",
                "saushs-koal",
                "ibenart",
                "drery",
                "gyuf-uin",
                "glynard",
                "hargra",
                "grewenn",
                "lamlyn",
            ),
        ),
        Place(
            zoneId = "sharlayan",
            campaignKey = "sharlayan",
            mgp = 400,
            members = listOf(
                "cheatingway",
                "ruissenaud",
                "aiglephine",
                "ghasa",
                "qetanur",
                "maillart",
                "celia",
                "mehryde",
                "gamingway",
                "worldly-imperial",
            ),
        ),
        Place(
            zoneId = "tural",
            campaignKey = "tural",
            mgp = 700,
            members = listOf(
                "nyikweni",
                "wopli",
                "uataaye",
                "warsowok",
                "bruk-noq",
                "luwyawa",
                "larisa",
                "hume-black-mage",
                "pudeel-ja",
                "pawkukwe",
            ),
        ),
        Place(
            zoneId = "balamb",
            campaignKey = "balamb",
            mgp = 200,
            members = listOf(
                "kid",
                "jack",
                "club",
                "diamond",
                "ma-dincht",
                "trepies",
            ),
        ),
        Place(
            zoneId = "galbadia",
            campaignKey = "galbadia",
            mgp = 300,
            members = listOf(
                "zone",
                "martine",
                "watts",
                "caraway",
            ),
        ),
        Place(
            zoneId = "fishermans-horizon",
            campaignKey = "fishermans-horizon",
            mgp = 300,
            members = listOf(
                "dobe",
                "flo",
            ),
        ),
        Place(
            zoneId = "centra",
            campaignKey = "centra",
            mgp = 300,
            members = listOf(
                "chocoboy",
                "ufo",
                "edea",
                "cid",
            ),
        ),
        Place(
            zoneId = "esthar",
            campaignKey = "esthar",
            mgp = 300,
            members = listOf(
                "piet",
                "odine",
                "ellone",
                "laguna",
            ),
        ),
        Place(
            zoneId = "card-club",
            campaignKey = "cc",
            mgp = 500,
            members = listOf(
                "spade",
                "jocker",
                "heart",
                "king",
            ),
        ),
    )

    /**
     * One achievement per place for clearing it, and one per tournament that has none yet.
     *
     * The three ladders that predate the map keep their `ac-cmp-*` achievement, authored in
     * [AchievementCatalog] and earned under those ids in live profiles; only the new ladders get
     * theirs here, under the same `ac-cmp-<key>` pattern.
     */
    fun achievements(existingCampaigns: Set<String>): List<Achievement> =
        PLACES.map { place ->
            Achievement(
                id = AchievementCatalog.placeCleared(place.zoneId),
                labelKey = "APP_AC_ZONE_${place.zoneId.labelStem()}",
                iconId = ICON,
                requirement = Requirement.NpcsBeaten(place.members),
                mgpReward = place.mgp,
            )
        } + PLACES.filter { it.campaignKey !in existingCampaigns }.map { place ->
            Achievement(
                id = "ac-cmp-${place.campaignKey}",
                labelKey = "APP_AC_CAMPAIGN_${place.campaignKey.labelStem()}",
                iconId = ICON,
                requirement = Requirement.CampaignWins(place.campaignKey),
            )
        }

    private fun String.labelStem(): String = uppercase().replace('-', '_')

    /** `Achievements.as:17`'s NPC icon, the one the Triple Team and tournament tiers wear. */
    private const val ICON = "000713"
}
