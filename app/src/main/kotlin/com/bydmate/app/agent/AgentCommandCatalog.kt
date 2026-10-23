package com.bydmate.app.agent

/**
 * Readable command-id catalog for the vehicle_control agent tool. The LLM only
 * ever sees these ids and their Polish descriptions (in the tool schema and
 * in error messages); the internal Chinese protocol strings dispatched to
 * ActionDispatcher never reach the model or the user. Ids are hand-written and
 * stable (not generated from VoiceCatalog at runtime) so the tool contract
 * does not shift if VoiceCatalog changes; coverage against VoiceCatalog is
 * enforced by AgentCommandCatalogTest.
 */
object AgentCommandCatalog {
    data class Cmd(
        val id: String,
        val label: String,
        val chinese: (Int?) -> String,
        val value: IntRange? = null,
    )

    val ALL: List<Cmd> = listOf(
        // Windows — composites
        Cmd("windows_open_all", "otwórz wszystkie okna", { "车窗全开" }),
        Cmd("windows_close_all", "zamknij wszystkie okna", { "车窗关闭" }),
        Cmd("windows_vent_all", "wszystkie okna dla wentylacji", { "车窗通风" }),
        Cmd("windows_half_all", "wszystkie okna są w połowie", { "车窗半开" }),
        Cmd("windows_open_front", "otwórz przednie okna", { "前排车窗全开" }),
        Cmd("windows_close_front", "zamknij przednie okna", { "前排车窗关闭" }),
        Cmd("windows_open_rear", "otwórz tylne okna", { "后排车窗全开" }),
        Cmd("windows_close_rear", "zamknij tylne okna", { "后排车窗关闭" }),
        Cmd("windows_half_front", "otwórz przednie okna do połowy", { "前排车窗半开" }),
        Cmd("windows_vent_front", "przewietrz przednie okna", { "前排车窗通风" }),
        Cmd("windows_half_rear", "połówka okien tylnych", { "后排车窗半开" }),
        Cmd("windows_vent_rear", "tylne okna do wentylacji", { "后排车窗通风" }),
        // Windows — individual (open=100 / close=0)
        Cmd("window_driver_open", "otwórz okno kierowcy", { "主驾打开100" }),
        Cmd("window_driver_close", "zamknij okno kierowcy", { "主驾打开0" }),
        Cmd("window_passenger_open", "otwórz okno przedniego pasażera", { "副驾打开100" }),
        Cmd("window_passenger_close", "zamknij okno przedniego pasażera", { "副驾打开0" }),
        Cmd("window_rear_left_open", "otwórz okno tylne lewe", { "后左打开100" }),
        Cmd("window_rear_left_close", "zamknij tylne lewe okno", { "后左打开0" }),
        Cmd("window_rear_right_open", "otwórz okno tylne prawe", { "后右打开100" }),
        Cmd("window_rear_right_close", "zamknij tylne prawe okno", { "后右打开0" }),
        // Windows — individual vent (crack one window for fresh air)
        Cmd("window_driver_vent", "przewietrz okno kierowcy", { "主驾通风" }),
        Cmd("window_passenger_vent", "przewietrz okno przedniego pasażera", { "副驾通风" }),
        Cmd("window_rear_left_vent", "przewietrz okno tylne lewe", { "后左通风" }),
        Cmd("window_rear_right_vent", "przewietrz okno tylne prawe", { "后右通风" }),
        // Windows — individual half (the 50 % detent)
        Cmd("window_driver_half", "otwórz do połowy okno kierowcy", { "主驾半开" }),
        Cmd("window_passenger_half", "otwórz do połowy okno przedniego pasażera", { "副驾半开" }),
        Cmd("window_rear_left_half", "tylne lewe okno połowa", { "后左半开" }),
        Cmd("window_rear_right_half", "tylne prawe okno połówkowe", { "后右半开" }),
        // Climate
        Cmd("ac_on", "włącz klimatyzację (auto)", { "自动空调" }),
        Cmd("ac_off", "wyłącz klimatyzację", { "关闭空调" }),
        Cmd("ac_flow_on", "włączyć wentylację klimatyczną (przepływ powietrza bez chłodzenia)", { "打开空调通风" }),
        Cmd("ac_set_temp", "ustaw temperaturę klimatyzacji na N°C", { n -> "设置温度$n" }, 16..33),
        Cmd("ac_fan_level", "ustaw prędkość wentylatora na N (1..7)", { n -> "风量$n" }, 1..7),
        Cmd("ac_wind_face", "nawiew na twarz", { "吹面" }),
        Cmd("ac_wind_face_feet", "nawiew na twarz i nogi", { "吹面吹脚" }),
        Cmd("ac_wind_feet", "nawiew na nogi", { "吹脚" }),
        Cmd("ac_wind_feet_windshield", "nawiew na nogi i szybę", { "吹脚除霜" }),
        Cmd("ac_wind_windshield", "nawiew na szybę", { "除霜" }),
        Cmd("ac_recirc_inner", "wewnętrzna recyrkulacja powietrza", { "内循环" }),
        Cmd("ac_recirc_outer", "obieg powietrza zewnętrznego", { "外循环" }),
        Cmd("defrost_front_on", "włącz przednią szybę", { "吹前挡" }),
        Cmd("defrost_front_off", "wyłącz dmuchawę przedniej szyby", { "关闭吹前挡" }),
        Cmd("ac_auto_on", "włącz tryb automatyczny klimatyzacji (tryb automatyczny)", { "空调自动" }),
        Cmd("ac_auto_off", "wyłącz tryb automatyczny klimatyzacji (tryb ręczny)", { "空调手动" }),
        // Seats — heat
        Cmd("seat_heat_driver_1", "ogrzewanie fotela kierowcy, poziom 1", { "主驾座椅加热1档" }),
        Cmd("seat_heat_driver_2", "ogrzewanie fotela kierowcy, poziom 2", { "主驾座椅加热2档" }),
        Cmd("seat_heat_driver_3", "ogrzewanie fotela kierowcy, poziom 3", { "主驾座椅加热3档" }),
        Cmd("seat_heat_driver_4", "ogrzewanie fotela kierowcy, poziom 4", { "主驾座椅加热4档" }),
        Cmd("seat_heat_driver_5", "ogrzewanie fotela kierowcy, poziom 5", { "主驾座椅加热5档" }),
        Cmd("seat_heat_driver_off", "wyłącz ogrzewanie fotela kierowcy", { "主驾座椅加热关闭" }),
        Cmd("seat_heat_passenger_1", "ogrzewanie fotela pasażera, poziom 1", { "副驾座椅加热1档" }),
        Cmd("seat_heat_passenger_2", "ogrzewanie fotela pasażera, poziom 2", { "副驾座椅加热2档" }),
        Cmd("seat_heat_passenger_3", "ogrzewanie fotela pasażera, poziom 3", { "副驾座椅加热3档" }),
        Cmd("seat_heat_passenger_4", "ogrzewanie fotela pasażera, poziom 4", { "副驾座椅加热4档" }),
        Cmd("seat_heat_passenger_5", "ogrzewanie fotela pasażera, poziom 5", { "副驾座椅加热5档" }),
        Cmd("seat_heat_passenger_off", "wyłącz ogrzewanie fotela pasażera", { "副驾座椅加热关闭" }),
        // Seats — vent
        Cmd("seat_vent_driver_1", "wentylacja fotela kierowcy poziom 1", { "主驾座椅通风1档" }),
        Cmd("seat_vent_driver_2", "wentylacja fotela kierowcy poziom 2", { "主驾座椅通风2档" }),
        Cmd("seat_vent_driver_3", "wentylacja fotela kierowcy poziom 3", { "主驾座椅通风3档" }),
        Cmd("seat_vent_driver_4", "wentylacja fotela kierowcy poziom 4", { "主驾座椅通风4档" }),
        Cmd("seat_vent_driver_5", "poziom wentylacji fotela kierowcy 5 (maksymalny)", { "主驾座椅通风5档" }),
        Cmd("seat_vent_driver_off", "wyłącz wentylację fotela kierowcy", { "主驾座椅通风关闭" }),
        Cmd("seat_vent_passenger_1", "wentylacja fotela pasażera poziom 1", { "副驾座椅通风1档" }),
        Cmd("seat_vent_passenger_2", "wentylacja fotela pasażera poziom 2", { "副驾座椅通风2档" }),
        Cmd("seat_vent_passenger_3", "wentylacja fotela pasażera poziom 3", { "副驾座椅通风3档" }),
        Cmd("seat_vent_passenger_4", "wentylacja fotela pasażera poziom 4", { "副驾座椅通风4档" }),
        Cmd("seat_vent_passenger_5", "wentylacja fotela pasażera poziom 5 (maksimum)", { "副驾座椅通风5档" }),
        Cmd("seat_vent_passenger_off", "wyłącz wentylację fotela pasażera", { "副驾座椅通风关闭" }),
        // Mirrors
        Cmd("mirror_heat_on", "włączyć ogrzewanie lusterek i tylnej szyby", { "后视镜加热" }),
        Cmd("mirror_heat_off", "wyłączyć ogrzewanie lusterek i tylnej szyby", { "关闭后视镜加热" }),
        // Steering wheel
        Cmd("steering_heat_on", "włącz podgrzewaną kierownicę", { "方向盘加热" }),
        Cmd("steering_heat_off", "wyłącz podgrzewaną kierownicę", { "关闭方向盘加热" }),
        // Lights
        Cmd("light_ambient_on", "załączenie oświetlenia atmosferycznego kabiny (otoczenia)", { "氛围灯打开" }),
        Cmd("light_ambient_off", "wyłączyć oświetlenie atmosferyczne kabiny (otoczenia)", { "氛围灯关闭" }),
        Cmd("light_drl_on", "włącz światła do jazdy dziennej", { "打开日行灯" }),
        Cmd("light_drl_off", "wyłącz światła do jazdy dziennej", { "关闭日行灯" }),
        Cmd("hazard_on", "włączyć stan awaryjny (alarm)", { "双闪打开" }),
        Cmd("hazard_off", "wyłączyć stan awaryjny", { "双闪关闭" }),
        Cmd("light_interior_on", "włącz klosz w kabinie", { "打开车内灯" }),
        Cmd("light_interior_off", "wyłącz abażur w kabinie", { "关闭车内灯" }),
        // HUD (projection on the windshield; the cluster projection is set_cluster_projection)
        Cmd("hud_on", "włącz HUD (projekcję przedniej szyby)", { "打开抬头显示" }),
        Cmd("hud_off", "wyłączyć HUD (rzut na przednią szybę)", { "关闭抬头显示" }),
        // Battery warm-up (the car itself refuses it on a warm battery or with a charge gun connected)
        Cmd("battery_preheat_on", "włącz wstępne podgrzewanie akumulatora trakcyjnego (przed szybkim ładowaniem w zimnej wodzie)", { "打开电池预热" }),
        Cmd("battery_preheat_off", "wyłącz ogrzewanie akumulatora trakcyjnego", { "关闭电池预热" }),
        // Locks
        Cmd("doors_lock", "zablokuj drzwi", { "车门上锁" }),
        Cmd("doors_unlock", "odblokuj drzwi", { "车门解锁" }),
        // Sunroof / sunshade
        Cmd("sunroof_open", "otwórz szyberdach", { "天窗打开100" }),
        Cmd("sunroof_half", "otwórz szyberdach do połowy", { "天窗打开50" }),
        Cmd("sunroof_close", "zamknij szyberdach", { "天窗打开0" }),
        Cmd("sunroof_stop", "zatrzymaj szyberdach", { "天窗停止" }),
        Cmd("sunroof_updip", "uchyl szyberdach dla wentylacji", { "天窗通风" }),
        Cmd("sunroof_comfort", "otwórz szyberdach do pozycji komfortowej", { "天窗舒适打开" }),
        Cmd("sunshade_open", "otwórz roletę szyberdachu", { "遮阳帘打开" }),
        Cmd("sunshade_close", "zamknij roletę szyberdachu", { "遮阳帘关闭" }),
        // Trunk (rear tailgate)
        Cmd("trunk_open", "otworzyć tylny bagażnik", { "开后备箱" }),
        Cmd("trunk_close", "zamknąć tylny bagażnik", { "关后备箱" }),
        // Front trunk (powered front storage; open is speed-0 gated by the dispatcher)
        Cmd("front_trunk_open", "otwórz przedni bagażnik (tylko na postoju)", { "前备箱打开" }),
        Cmd("front_trunk_close", "zamknij przedni bagażnik", { "前备箱关闭" }),
        // Fridge (center-console icebox)
        Cmd("fridge_cool", "chłodzenie lodówki do N stopni", { n -> "冰箱制冷${n}度" }, -6..6),
        Cmd("fridge_heat", "ogrzewanie lodówki do N stopni", { n -> "冰箱制热${n}度" }, 35..50),
        Cmd("fridge_off", "wyłącz lodówkę", { "冰箱关闭" }),
        // Drive mode (the car checks it has the mode; all but eco/normal/sport only up to 15 km/h)
        Cmd("drive_mode_eco", "tryb jazdy Eco", { "ECO模式" }),
        Cmd("drive_mode_normal", "tryb jazdy Normalny", { "普通模式" }),
        Cmd("drive_mode_sport", "tryb jazdy Sport", { "运动模式" }),
        Cmd("drive_mode_snow", "tryb jazdy Śnieg (do 15 km/h)", { "雪地模式" }),
        Cmd("drive_mode_sand", "tryb jazdy Piasek (do 15 km/h)", { "沙地模式" }),
        Cmd("drive_mode_mud", "tryb jazdy Błoto (do 15 km/h)", { "泥地模式" }),
        Cmd("drive_mode_mountain", "tryb jazdy Góry (do 15 km/h)", { "山地模式" }),
        Cmd("drive_mode_rock", "tryb jazdy Skały (do 15 km/h)", { "岩石模式" }),
        Cmd("drive_mode_smart", "inteligentny tryb jazdy (do 15 km/h)", { "智能模式" }),
    )

    private val byId = ALL.associateBy { it.id }

    /** Resolve a readable id (+ optional value) to the internal Chinese
     *  dispatch string, or null if the id is unknown, or the value is missing
     *  / out of range for a ranged command. */
    fun resolve(id: String, value: Int?): String? {
        val cmd = byId[id] ?: return null
        val range = cmd.value
        if (range != null) {
            val v = value ?: return null
            if (v !in range) return null
            return cmd.chinese(v)
        }
        return cmd.chinese(null)
    }

    /** id — Polish description, one per line, for the vehicle_control tool description. */
    fun idsDoc(): String = ALL.joinToString("\n") { c ->
        if (c.value == null) "${c.id} — ${c.label}"
        else "${c.id} — ${c.label} (value=${c.value.first}..${c.value.last})"
    }
}
