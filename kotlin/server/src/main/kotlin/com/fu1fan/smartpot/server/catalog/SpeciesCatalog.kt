package com.fu1fan.smartpot.server.catalog

import com.fu1fan.smartpot.protocol.PlantSpecies
import com.fu1fan.smartpot.protocol.PlantThresholds

object SpeciesCatalog {
    private const val SensorLuxDivisor = 3
    private const val SensorLuxStep = 50

    private data class Seed(
        val id: String,
        val zh: String,
        val scientific: String,
        val soil: IntRange,
        val light: IntRange,
        val waterDays: Int,
        val fertilizerDays: Int,
        val note: String,
    )

    private val seeds = listOf(
        Seed("pothos", "绿萝", "Epipremnum aureum", 40..70, 500..8000, 7, 30, "喜温暖湿润和明亮散射光，避免长期积水与烈日直射。"),
        Seed("succulent", "多肉植物", "Succulentae", 15..40, 1000..18000, 14, 45, "耐旱，浇透后充分干燥，保持通风和充足光照。"),
        Seed("rose", "月季", "Rosa chinensis", 40..65, 5000..30000, 4, 14, "需要充足光照和通风，生长期保持盆土湿润但不积水。"),
        Seed("orchid", "兰花", "Cymbidium spp.", 35..60, 800..8000, 7, 21, "偏好散射光和透气植料，避免叶心积水。"),
        Seed("monstera", "龟背竹", "Monstera deliciosa", 40..70, 800..10000, 7, 30, "喜温暖湿润和散射光，表土干后再浇透。"),
        Seed("spider-plant", "吊兰", "Chlorophytum comosum", 35..65, 600..9000, 6, 30, "适应性强，避免盆土长期过湿。"),
        Seed("peace-lily", "白掌", "Spathiphyllum wallisii", 45..75, 500..7000, 5, 25, "喜湿润和散射光，缺水时叶片易下垂。"),
        Seed("snake-plant", "虎尾兰", "Dracaena trifasciata", 15..45, 500..12000, 14, 45, "耐旱耐阴，低温时减少浇水。"),
        Seed("rubber-plant", "橡皮树", "Ficus elastica", 30..60, 1000..12000, 9, 30, "明亮散射光下生长良好，浇水遵循见干见湿。"),
        Seed("ficus-lyrata", "琴叶榕", "Ficus lyrata", 35..65, 1500..15000, 7, 30, "保持稳定光照，避免频繁搬动和根部积水。"),
        Seed("aloe", "芦荟", "Aloe vera", 15..40, 2000..20000, 14, 60, "耐旱，使用排水良好的介质。"),
        Seed("cactus", "仙人掌", "Cactaceae", 10..35, 3000..30000, 20, 60, "强光环境生长良好，浇水宁少勿多。"),
        Seed("money-tree", "发财树", "Pachira aquatica", 25..55, 1000..10000, 10, 35, "根系怕涝，保持温暖和明亮散射光。"),
        Seed("zz-plant", "金钱树", "Zamioculcas zamiifolia", 15..45, 400..9000, 15, 45, "耐阴耐旱，盆土干透后再浇。"),
        Seed("dracaena", "龙血树", "Dracaena fragrans", 30..60, 800..10000, 9, 35, "适合明亮散射光，避免冷风和积水。"),
        Seed("ivy", "常春藤", "Hedera helix", 35..65, 800..10000, 6, 30, "喜凉爽通风和散射光，空气干燥时注意叶螨。"),
        Seed("boston-fern", "波士顿蕨", "Nephrolepis exaltata", 50..80, 500..6000, 4, 20, "喜湿润和高空气湿度，避免强光。"),
        Seed("calathea", "竹芋", "Goeppertia spp.", 45..75, 400..5000, 5, 25, "偏好柔和散射光和稳定湿润环境。"),
        Seed("begonia", "秋海棠", "Begonia spp.", 35..65, 800..9000, 6, 20, "保持通风，避免叶面长时间积水。"),
        Seed("african-violet", "非洲堇", "Streptocarpus ionanthus", 35..60, 800..7000, 6, 20, "喜温暖散射光，建议从盆底浇水。"),
        Seed("geranium", "天竺葵", "Pelargonium hortorum", 25..55, 2500..18000, 6, 20, "需要充足光照和良好通风。"),
        Seed("hydrangea", "绣球", "Hydrangea macrophylla", 50..80, 1200..10000, 3, 15, "需水量较大，夏季避开午后强光。"),
        Seed("jasmine", "茉莉", "Jasminum sambac", 40..65, 4000..25000, 4, 14, "喜光喜肥，花期保持水分但避免积水。"),
        Seed("gardenia", "栀子花", "Gardenia jasminoides", 45..70, 2500..18000, 4, 14, "喜酸性土和充足散射光，忌碱性积水。"),
        Seed("camellia", "茶花", "Camellia japonica", 40..65, 1500..12000, 6, 25, "喜酸性、疏松介质和凉爽散射光。"),
        Seed("azalea", "杜鹃", "Rhododendron simsii", 45..70, 1200..10000, 5, 20, "根系细弱，保持微湿但不能闷根。"),
        Seed("chrysanthemum", "菊花", "Chrysanthemum morifolium", 35..60, 3500..22000, 5, 15, "需要充足日照和通风，花期避免过度干旱。"),
        Seed("lavender", "薰衣草", "Lavandula angustifolia", 15..40, 5000..30000, 12, 40, "喜强光、干燥和通风，忌高湿积水。"),
        Seed("mint", "薄荷", "Mentha canadensis", 45..75, 1500..16000, 3, 20, "生长旺盛且需水较多，定期修剪。"),
        Seed("basil", "罗勒", "Ocimum basilicum", 40..70, 3000..22000, 4, 18, "喜温暖和充足光照，及时摘心促进分枝。"),
        Seed("rosemary", "迷迭香", "Salvia rosmarinus", 15..45, 5000..30000, 10, 35, "喜强光和通风，盆土偏干管理。"),
        Seed("thyme", "百里香", "Thymus vulgaris", 15..45, 5000..30000, 10, 40, "耐旱喜光，避免高湿和闷热。"),
        Seed("parsley", "欧芹", "Petroselinum crispum", 35..65, 2500..18000, 5, 20, "保持均匀湿润和充足光照。"),
        Seed("strawberry", "草莓", "Fragaria × ananassa", 40..70, 4000..25000, 4, 12, "开花结果期保持充足光照与稳定水肥。"),
        Seed("tomato", "番茄", "Solanum lycopersicum", 45..70, 8000..40000, 3, 10, "需强光和充足水肥，保持根区通风。"),
        Seed("pepper", "辣椒", "Capsicum annuum", 35..65, 7000..35000, 4, 12, "喜温暖强光，结果期避免忽干忽湿。"),
        Seed("lemon", "柠檬", "Citrus limon", 35..65, 6000..35000, 6, 18, "需要充足日照、酸性土和规律施肥。"),
        Seed("kumquat", "金桔", "Citrus japonica", 35..65, 6000..35000, 6, 18, "喜光，结果期保持水分稳定。"),
        Seed("bonsai-ficus", "榕树盆景", "Ficus microcarpa", 35..65, 1800..15000, 7, 25, "喜温暖明亮环境，避免突然改变光照。"),
        Seed("bamboo-palm", "散尾葵", "Dypsis lutescens", 40..70, 800..10000, 6, 30, "喜温暖湿润和散射光，叶尖干枯时检查水分。"),
        Seed("anthurium", "红掌", "Anthurium andraeanum", 40..70, 700..8000, 6, 22, "偏好温暖高湿和明亮散射光。"),
        Seed("cyclamen", "仙客来", "Cyclamen persicum", 35..60, 1200..10000, 6, 20, "喜凉爽，休眠期减少浇水。"),
        Seed("clivia", "君子兰", "Clivia miniata", 30..55, 600..7000, 9, 30, "喜散射光，肉质根怕积水。"),
        Seed("peperomia", "豆瓣绿", "Peperomia obtusifolia", 25..55, 600..8000, 10, 35, "叶片储水，浇水不宜过勤。"),
        Seed("pilea", "镜面草", "Pilea peperomioides", 35..65, 800..9000, 7, 28, "喜明亮散射光，定期转盆保持株形。"),
        Seed("string-of-pearls", "佛珠", "Curio rowleyanus", 15..40, 1800..16000, 14, 45, "多肉质，介质需排水良好。"),
        Seed("jade-plant", "玉树", "Crassula ovata", 15..40, 2500..22000, 14, 45, "耐旱喜光，避免低温积水。"),
        Seed("echeveria", "石莲花", "Echeveria spp.", 10..35, 4000..30000, 16, 50, "充足光照下株形紧凑，避免叶心积水。"),
        Seed("philodendron", "喜林芋", "Philodendron spp.", 35..65, 700..9000, 8, 30, "喜温暖散射光，保持介质疏松透气。"),
        Seed("dieffenbachia", "花叶万年青", "Dieffenbachia seguine", 40..70, 600..8000, 7, 30, "避免强光和低温，汁液有刺激性。"),
    )

    val all: List<PlantSpecies> = seeds.map { seed ->
        val sensorLightRange = seed.light.toBh1750SensorLuxRange()
        PlantSpecies(
            id = seed.id,
            chineseName = seed.zh,
            scientificName = seed.scientific,
            thresholds = PlantThresholds(
                soilMinPercent = seed.soil.first,
                soilMaxPercent = seed.soil.last,
                lightMinLux = sensorLightRange.first,
                lightMaxLux = sensorLightRange.last,
                temperatureMinC = 15.0,
                temperatureMaxC = 30.0,
            ),
            wateringIntervalDays = seed.waterDays,
            fertilizingIntervalDays = seed.fertilizerDays,
            pruningIntervalDays = 60,
            repottingIntervalDays = 365,
            knowledge = seed.note,
        )
    }

    private fun IntRange.toBh1750SensorLuxRange(): IntRange {
        val min = first.toBh1750SensorLux()
        val max = last.toBh1750SensorLux().coerceAtLeast(min)
        return min..max
    }

    private fun Int.toBh1750SensorLux(): Int {
        val scaled = (this + SensorLuxDivisor - 1) / SensorLuxDivisor
        return (((scaled + SensorLuxStep - 1) / SensorLuxStep) * SensorLuxStep)
            .coerceAtLeast(SensorLuxStep)
    }
}
