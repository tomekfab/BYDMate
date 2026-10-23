package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.util.appLocalizedContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour-lock for the automation i18n catalog. GENERATED from the original
 * inline pl/zh/en literals (baseline.json) — proves the resource migration
 * preserves every name/category 1:1 across pl/en/zh, and every unit/enum label
 * in pl. Designed to stay green BOTH before and after the refactor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AutomationI18nGoldenTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun lang(l: String) = LocalePreferences(ctx).setLanguage(l)

    // command -> Triple(pl, en, zh) name ; and category triple
    private val actName = mapOf(
        "车窗通风" to Triple("Wietrzenie", "Vent Windows", "车窗通风"),
        "车窗关闭" to Triple("Zamknij wszystkie szyby", "Close All Windows", "关闭所有车窗"),
        "车窗全开" to Triple("Otwórz wszystkie szyby", "Open All Windows", "打开所有车窗"),
        "车窗半开" to Triple("Wszystkie szyby na 50%", "All Windows 50%", "所有车窗半开"),
        "前排车窗关闭" to Triple("Zamknij przednie", "Close Front Windows", "关闭前排车窗"),
        "后排车窗关闭" to Triple("Zamknij tylne", "Close Rear Windows", "关闭后排车窗"),
        "前排车窗全开" to Triple("Otwórz przednie", "Open Front Windows", "打开前排车窗"),
        "后排车窗全开" to Triple("Otwórz tylne", "Open Rear Windows", "打开后排车窗"),
        "主驾打开100" to Triple("Otwórz szybę kierowcy", "Open Driver Window", "打开主驾车窗"),
        "主驾打开0" to Triple("Zamknij szybę kierowcy", "Close Driver Window", "关闭主驾车窗"),
        "副驾打开100" to Triple("Otwórz szybę pasażera", "Open Passenger Window", "打开副驾车窗"),
        "副驾打开0" to Triple("Zamknij szybę pasażera", "Close Passenger Window", "关闭副驾车窗"),
        "后左打开100" to Triple("Otwórz tylną lewą szybę", "Open Rear Left Window", "打开后左车窗"),
        "后左打开0" to Triple("Zamknij tylną lewą szybę", "Close Rear Left Window", "关闭后左车窗"),
        "后右打开100" to Triple("Otwórz tylną prawą szybę", "Open Rear Right Window", "打开后右车窗"),
        "后右打开0" to Triple("Zamknij tylną prawą szybę", "Close Rear Right Window", "关闭后右车窗"),
        "主驾通风" to Triple("Przewietrz szybę kierowcy", "Vent Driver Window", "主驾车窗通风"),
        "副驾通风" to Triple("Przewietrz szybę pasażera", "Vent Passenger Window", "副驾车窗通风"),
        "后左通风" to Triple("Przewietrz tylną lewą szybę", "Vent Rear Left Window", "后左车窗通风"),
        "后右通风" to Triple("Przewietrz tylną prawą szybę", "Vent Rear Right Window", "后右车窗通风"),
        "主驾半开" to Triple("Szyba kierowcy na 50%", "Driver Window 50%", "主驾车窗半开"),
        "副驾半开" to Triple("Szyba pasażera na 50%", "Passenger Window 50%", "副驾车窗半开"),
        "后左半开" to Triple("Tylna lewa szyba na 50%", "Rear Left Window 50%", "后左车窗半开"),
        "后右半开" to Triple("Tylna prawa szyba na 50%", "Rear Right Window 50%", "后右车窗半开"),
        "前排车窗半开" to Triple("Przednie na 50%", "Front Windows 50%", "前排车窗半开"),
        "前排车窗通风" to Triple("Przewietrz przednie", "Vent Front Windows", "前排车窗通风"),
        "后排车窗半开" to Triple("Tylne na 50%", "Rear Windows 50%", "后排车窗半开"),
        "后排车窗通风" to Triple("Przewietrz tylne", "Vent Rear Windows", "后排车窗通风"),
        "自动空调" to Triple("Auto AC", "Auto AC", "自动空调"),
        "打开空调通风" to Triple("Nawiew bez AC", "Ventilation (no AC)", "通风（不开AC）"),
        "吹面" to Triple("Nawiew: na twarz", "Airflow: face", "出风：吹面"),
        "吹面吹脚" to Triple("Nawiew: twarz i nogi", "Airflow: face and feet", "出风：吹面吹脚"),
        "吹脚" to Triple("Nawiew: na nogi", "Airflow: feet", "出风：吹脚"),
        "吹脚除霜" to Triple("Nawiew: nogi i szyba", "Airflow: feet and windshield", "出风：吹脚除霜"),
        "除霜" to Triple("Nawiew: na szybę", "Airflow: windshield", "出风：除霜"),
        "内循环" to Triple("Obieg wewnętrzny", "Recirculation", "内循环"),
        "外循环" to Triple("Obieg zewnętrzny", "Fresh Air", "外循环"),
        "吹前挡" to Triple("Nawiew na przednią szybę wł.", "Windshield Defog On", "前挡风除雾开"),
        "关闭吹前挡" to Triple("Nawiew na przednią szybę wył.", "Windshield Defog Off", "前挡风除雾关"),
        "关闭空调" to Triple("Wyłącz klimatyzację", "AC Off", "关闭空调"),
        "空调自动" to Triple("Klimatyzacja: tryb auto", "AC Auto Mode", "空调自动"),
        "空调手动" to Triple("Klimatyzacja: tryb ręczny", "AC Manual Mode", "空调手动"),
        "后视镜加热" to Triple("Podgrzewanie lusterek wł.", "Mirror Heat On", "后视镜加热开"),
        "关闭后视镜加热" to Triple("Podgrzewanie lusterek wył.", "Mirror Heat Off", "后视镜加热关"),
        "方向盘加热" to Triple("Podgrzewanie kierownicy wł.", "Steering Wheel Heat On", "方向盘加热开"),
        "关闭方向盘加热" to Triple("Podgrzewanie kierownicy wył.", "Steering Wheel Heat Off", "方向盘加热关"),
        "ECO模式" to Triple("Tryb ECO", "ECO mode", "ECO模式"),
        "普通模式" to Triple("Tryb normalny", "Normal mode", "普通模式"),
        "运动模式" to Triple("Tryb sportowy", "Sport mode", "运动模式"),
        "雪地模式" to Triple("Tryb śnieg", "Snow mode", "雪地模式"),
        "沙地模式" to Triple("Tryb piasek", "Sand mode", "沙地模式"),
        "泥地模式" to Triple("Tryb błoto", "Mud mode", "泥地模式"),
        "山地模式" to Triple("Tryb górski", "Mountain mode", "山地模式"),
        "岩石模式" to Triple("Tryb skały", "Rock mode", "岩石模式"),
        "智能模式" to Triple("Tryb inteligentny", "Smart mode", "智能模式"),
        "氛围灯打开" to Triple("Oświetlenie ambient wł.", "Ambient Light On", "氛围灯开"),
        "氛围灯关闭" to Triple("Oświetlenie ambient wył.", "Ambient Light Off", "氛围灯关"),
        "打开日行灯" to Triple("Światła dzienne wł.", "DRL On", "日行灯开"),
        "关闭日行灯" to Triple("Światła dzienne wył.", "DRL Off", "日行灯关"),
        "双闪打开" to Triple("Awaryjne: włącz", "Hazard lights: on", "打开双闪"),
        "双闪关闭" to Triple("Awaryjne: wyłącz", "Hazard lights: off", "关闭双闪"),
        "打开车内灯" to Triple("Oświetlenie wnętrza wł.", "Interior Light On", "车内灯开"),
        "关闭车内灯" to Triple("Oświetlenie wnętrza wył.", "Interior Light Off", "车内灯关"),
        "打开抬头显示" to Triple("HUD: włącz", "HUD: on", "打开抬头显示"),
        "关闭抬头显示" to Triple("HUD: wyłącz", "HUD: off", "关闭抬头显示"),
        "打开电池预热" to Triple("Podgrzewanie baterii: włącz", "Battery warm-up: on", "打开电池预热"),
        "关闭电池预热" to Triple("Podgrzewanie baterii: wyłącz", "Battery warm-up: off", "关闭电池预热"),
        "车门上锁" to Triple("Zablokuj", "Lock Doors", "车门上锁"),
        "车门解锁" to Triple("Odblokuj", "Unlock Doors", "车门解锁"),
        "天窗打开100" to Triple("Szyberdach otwórz 100%", "Sunroof Open 100%", "天窗全开"),
        "天窗打开50" to Triple("Szyberdach otwórz 50%", "Sunroof Open 50%", "天窗半开"),
        "天窗打开0" to Triple("Szyberdach zamknij", "Sunroof Close", "天窗关闭"),
        "遮阳帘打开" to Triple("Roleta otwórz", "Sunshade Open", "遮阳帘打开"),
        "遮阳帘关闭" to Triple("Roleta zamknij", "Sunshade Close", "遮阳帘关闭"),
        "天窗停止" to Triple("Szyberdach zatrzymaj", "Sunroof Stop", "天窗停止"),
        "天窗通风" to Triple("Szyberdach uchyl", "Sunroof Tilt", "天窗通风"),
        "天窗舒适打开" to Triple("Szyberdach komfort", "Sunroof Comfort", "天窗舒适打开"),
        "开后备箱" to Triple("Otwórz bagażnik", "Open Trunk", "打开后备箱"),
        "关后备箱" to Triple("Zamknij bagażnik", "Close Trunk", "关闭后备箱"),
        "前备箱打开" to Triple("Otwórz przedni bagażnik", "Open Front Trunk", "打开前备箱"),
        "前备箱关闭" to Triple("Zamknij przedni bagażnik", "Close Front Trunk", "关闭前备箱"),
        "冰箱制冷" to Triple("Lodówka: chłodzenie", "Fridge Cool", "冰箱制冷"),
        "冰箱制热" to Triple("Lodówka: grzanie", "Fridge Heat", "冰箱制热"),
        "冰箱关闭" to Triple("Lodówka: wył.", "Fridge Off", "冰箱关闭"),
    )
    private val actCat = mapOf(
        "车窗通风" to Triple("Szyby", "Windows", "车窗"),
        "车窗关闭" to Triple("Szyby", "Windows", "车窗"),
        "车窗全开" to Triple("Szyby", "Windows", "车窗"),
        "车窗半开" to Triple("Szyby", "Windows", "车窗"),
        "前排车窗关闭" to Triple("Szyby", "Windows", "车窗"),
        "后排车窗关闭" to Triple("Szyby", "Windows", "车窗"),
        "前排车窗全开" to Triple("Szyby", "Windows", "车窗"),
        "后排车窗全开" to Triple("Szyby", "Windows", "车窗"),
        "主驾打开100" to Triple("Szyby", "Windows", "车窗"),
        "主驾打开0" to Triple("Szyby", "Windows", "车窗"),
        "副驾打开100" to Triple("Szyby", "Windows", "车窗"),
        "副驾打开0" to Triple("Szyby", "Windows", "车窗"),
        "后左打开100" to Triple("Szyby", "Windows", "车窗"),
        "后左打开0" to Triple("Szyby", "Windows", "车窗"),
        "后右打开100" to Triple("Szyby", "Windows", "车窗"),
        "后右打开0" to Triple("Szyby", "Windows", "车窗"),
        "主驾通风" to Triple("Szyby", "Windows", "车窗"),
        "副驾通风" to Triple("Szyby", "Windows", "车窗"),
        "后左通风" to Triple("Szyby", "Windows", "车窗"),
        "后右通风" to Triple("Szyby", "Windows", "车窗"),
        "主驾半开" to Triple("Szyby", "Windows", "车窗"),
        "副驾半开" to Triple("Szyby", "Windows", "车窗"),
        "后左半开" to Triple("Szyby", "Windows", "车窗"),
        "后右半开" to Triple("Szyby", "Windows", "车窗"),
        "前排车窗半开" to Triple("Szyby", "Windows", "车窗"),
        "前排车窗通风" to Triple("Szyby", "Windows", "车窗"),
        "后排车窗半开" to Triple("Szyby", "Windows", "车窗"),
        "后排车窗通风" to Triple("Szyby", "Windows", "车窗"),
        "自动空调" to Triple("Klimatyzacja", "Climate", "空调"),
        "打开空调通风" to Triple("Klimatyzacja", "Climate", "空调"),
        "吹面" to Triple("Klimatyzacja", "Climate", "空调"),
        "吹面吹脚" to Triple("Klimatyzacja", "Climate", "空调"),
        "吹脚" to Triple("Klimatyzacja", "Climate", "空调"),
        "吹脚除霜" to Triple("Klimatyzacja", "Climate", "空调"),
        "除霜" to Triple("Klimatyzacja", "Climate", "空调"),
        "内循环" to Triple("Klimatyzacja", "Climate", "空调"),
        "外循环" to Triple("Klimatyzacja", "Climate", "空调"),
        "吹前挡" to Triple("Klimatyzacja", "Climate", "空调"),
        "关闭吹前挡" to Triple("Klimatyzacja", "Climate", "空调"),
        "关闭空调" to Triple("Klimatyzacja", "Climate", "空调"),
        "空调自动" to Triple("Klimatyzacja", "Climate", "空调"),
        "空调手动" to Triple("Klimatyzacja", "Climate", "空调"),
        "后视镜加热" to Triple("Lusterka", "Mirrors", "后视镜"),
        "关闭后视镜加热" to Triple("Lusterka", "Mirrors", "后视镜"),
        "方向盘加热" to Triple("Klimatyzacja", "Climate", "空调"),
        "关闭方向盘加热" to Triple("Klimatyzacja", "Climate", "空调"),
        "ECO模式" to Triple("Tryb jazdy", "Drive mode", "驾驶模式"),
        "普通模式" to Triple("Tryb jazdy", "Drive mode", "驾驶模式"),
        "运动模式" to Triple("Tryb jazdy", "Drive mode", "驾驶模式"),
        "雪地模式" to Triple("Tryb jazdy", "Drive mode", "驾驶模式"),
        "沙地模式" to Triple("Tryb jazdy", "Drive mode", "驾驶模式"),
        "泥地模式" to Triple("Tryb jazdy", "Drive mode", "驾驶模式"),
        "山地模式" to Triple("Tryb jazdy", "Drive mode", "驾驶模式"),
        "岩石模式" to Triple("Tryb jazdy", "Drive mode", "驾驶模式"),
        "智能模式" to Triple("Tryb jazdy", "Drive mode", "驾驶模式"),
        "氛围灯打开" to Triple("Światła", "Light", "灯光"),
        "氛围灯关闭" to Triple("Światła", "Light", "灯光"),
        "打开日行灯" to Triple("Światła", "Light", "灯光"),
        "关闭日行灯" to Triple("Światła", "Light", "灯光"),
        "双闪打开" to Triple("Światła", "Light", "灯光"),
        "双闪关闭" to Triple("Światła", "Light", "灯光"),
        "打开车内灯" to Triple("Światła", "Light", "灯光"),
        "关闭车内灯" to Triple("Światła", "Light", "灯光"),
        "打开抬头显示" to Triple("Światła", "Light", "灯光"),
        "关闭抬头显示" to Triple("Światła", "Light", "灯光"),
        "打开电池预热" to Triple("Energia", "Energy", "能源"),
        "关闭电池预热" to Triple("Energia", "Energy", "能源"),
        "车门上锁" to Triple("Zamki", "Locks", "门锁"),
        "车门解锁" to Triple("Zamki", "Locks", "门锁"),
        "天窗打开100" to Triple("Szyberdach", "Sunroof", "天窗"),
        "天窗打开50" to Triple("Szyberdach", "Sunroof", "天窗"),
        "天窗打开0" to Triple("Szyberdach", "Sunroof", "天窗"),
        "遮阳帘打开" to Triple("Szyberdach", "Sunroof", "天窗"),
        "遮阳帘关闭" to Triple("Szyberdach", "Sunroof", "天窗"),
        "天窗停止" to Triple("Szyberdach", "Sunroof", "天窗"),
        "天窗通风" to Triple("Szyberdach", "Sunroof", "天窗"),
        "天窗舒适打开" to Triple("Szyberdach", "Sunroof", "天窗"),
        "开后备箱" to Triple("Nadwozie", "Body", "车身"),
        "关后备箱" to Triple("Nadwozie", "Body", "车身"),
        "前备箱打开" to Triple("Nadwozie", "Body", "车身"),
        "前备箱关闭" to Triple("Nadwozie", "Body", "车身"),
        "冰箱制冷" to Triple("Lodówka", "Fridge", "冰箱"),
        "冰箱制热" to Triple("Lodówka", "Fridge", "冰箱"),
        "冰箱关闭" to Triple("Lodówka", "Fridge", "冰箱"),
    )
    private val parName = mapOf(
        "Speed" to Triple("Prędkość", "Speed", "车速"),
        "Gear" to Triple("Bieg", "Gear", "档位"),
        "DriveMode" to Triple("Tryb jazdy", "Drive Mode", "整车运行模式"),
        "TurnSignal" to Triple("Kierunkowskaz", "Turn signal", "转向灯"),
        "SOC" to Triple("SOC", "SOC", "电量百分比"),
        "ChargingStatus" to Triple("Status ładowania", "Charging Status", "充电状态"),
        "Voltage12V" to Triple("Akumulator 12V", "12V Battery", "蓄电池电压"),
        "MinCellVoltage" to Triple("Min. napięcie ogniwa", "Min Cell Voltage", "单体最低电压"),
        "MaxCellVoltage" to Triple("Maks. napięcie ogniwa", "Max Cell Voltage", "单体最高电压"),
        "RangeKm" to Triple("Zasięg", "Range", "续航里程"),
        "ExtTemp" to Triple("Temperatura na zewnątrz", "Outside Temperature", "车外温度"),
        "InsideTemp" to Triple("Temp. wnętrza", "Cabin Temp", "车内温度"),
        "AvgBatTemp" to Triple("Temp. akumulatora", "Battery Temp", "平均电池温度"),
        "WindowFL" to Triple("Szyba kierowcy", "Driver Window", "主驾车窗打开百分比"),
        "WindowFR" to Triple("Szyba pasażera", "Passenger Window", "副驾车窗打开百分比"),
        "WindowRL" to Triple("Szyba tylna lewa", "Rear Left Window", "左后车窗"),
        "WindowRR" to Triple("Szyba tylna prawa", "Rear Right Window", "右后车窗"),
        "Sunroof" to Triple("Szyberdach", "Sunroof", "天窗打开百分比"),
        "DoorFL" to Triple("Drzwi kierowcy", "Driver Door", "主驾车门"),
        "DoorFR" to Triple("Drzwi pasażera", "Passenger Door", "副驾车门"),
        "DoorRL" to Triple("Drzwi tylne lewe", "Rear Left Door", "左后车门"),
        "DoorRR" to Triple("Drzwi tylne prawe", "Rear Right Door", "右后车门"),
        "Hood" to Triple("Maska", "Hood", "引擎盖"),
        "LockFL" to Triple("Zamek drzwi kierowcy", "Driver Door Lock", "主驾车门锁"),
        "Trunk" to Triple("Bagażnik", "Trunk", "后备箱门"),
        "ACStatus" to Triple("Klimatyzacja", "AC Status", "空调状态"),
        "ACCirc" to Triple("Tryb obiegu", "AC Circulation", "空调循环方式"),
        "ACTemp" to Triple("Temp. AC", "AC Temp", "主驾驶空调温度"),
        "FanLevel" to Triple("Dmuchawa", "Fan Level", "风量档位"),
        "SeatbeltFL" to Triple("Pas kierowcy", "Driver Seatbelt", "主驾驶安全带状态"),
        "SeatbeltFR" to Triple("Pas pasażera", "Passenger Seatbelt", "副驾安全带"),
        "SeatbeltRL" to Triple("Pas z tyłu po lewej", "Rear Left Seatbelt", "左后安全带"),
        "SeatbeltRM" to Triple("Pas z tyłu na środku", "Rear Middle Seatbelt", "后中安全带"),
        "SeatbeltRR" to Triple("Pas z tyłu po prawej", "Rear Right Seatbelt", "右后安全带"),
        "OccupancyFL" to Triple("Fotel kierowcy", "Driver Seat", "主驾座椅占用"),
        "OccupancyFR" to Triple("Fotel pasażera z przodu", "Front Passenger Seat", "副驾座椅占用"),
        "OccupancyRL" to Triple("Fotel z tyłu po lewej", "Rear Left Seat", "左后座椅占用"),
        "OccupancyRM" to Triple("Fotel z tyłu na środku", "Rear Middle Seat", "后中座椅占用"),
        "OccupancyRR" to Triple("Fotel z tyłu po prawej", "Rear Right Seat", "右后座椅占用"),
        "KeyBattery" to Triple("Bateria kluczyka", "Key Fob Battery", "钥匙电池"),
        "LightLevel" to Triple("Natężenie światła", "Ambient Light", "光照等级"),
        "TirePressFL" to Triple("Ciśnienie lewej przedniej opony", "Front Left Tire Pressure", "左前轮气压"),
        "TirePressFR" to Triple("Ciśnienie prawej przedniej opony", "Front Right Tire Pressure", "右前轮气压"),
        "TirePressRL" to Triple("Ciśnienie lewej tylnej opony", "Rear Left Tire Pressure", "左后轮气压"),
        "TirePressRR" to Triple("Ciśnienie prawej tylnej opony", "Rear Right Tire Pressure", "右后轮气压"),
        "Rain" to Triple("Czujnik deszczu", "Rain Sensor", "雨量"),
        "LightLow" to Triple("Światła mijania", "Low Beam", "近光灯"),
        "DRL" to Triple("Światła do jazdy dziennej", "DRL", "日行灯"),
    )
    private val parCat = mapOf(
        "Speed" to Triple("Jazda", "Driving", "行驶"),
        "Gear" to Triple("Jazda", "Driving", "行驶"),
        "DriveMode" to Triple("Jazda", "Driving", "行驶"),
        "TurnSignal" to Triple("Jazda", "Driving", "行驶"),
        "SOC" to Triple("Energia", "Energy", "能源"),
        "ChargingStatus" to Triple("Energia", "Energy", "能源"),
        "Voltage12V" to Triple("Energia", "Energy", "能源"),
        "MinCellVoltage" to Triple("Energia", "Energy", "能源"),
        "MaxCellVoltage" to Triple("Energia", "Energy", "能源"),
        "RangeKm" to Triple("Energia", "Energy", "能源"),
        "ExtTemp" to Triple("Temperatura", "Temperature", "温度"),
        "InsideTemp" to Triple("Temperatura", "Temperature", "温度"),
        "AvgBatTemp" to Triple("Temperatura", "Temperature", "温度"),
        "WindowFL" to Triple("Nadwozie", "Body", "车身"),
        "WindowFR" to Triple("Nadwozie", "Body", "车身"),
        "WindowRL" to Triple("Nadwozie", "Body", "车身"),
        "WindowRR" to Triple("Nadwozie", "Body", "车身"),
        "Sunroof" to Triple("Nadwozie", "Body", "车身"),
        "DoorFL" to Triple("Nadwozie", "Body", "车身"),
        "DoorFR" to Triple("Nadwozie", "Body", "车身"),
        "DoorRL" to Triple("Nadwozie", "Body", "车身"),
        "DoorRR" to Triple("Nadwozie", "Body", "车身"),
        "Hood" to Triple("Nadwozie", "Body", "车身"),
        "LockFL" to Triple("Nadwozie", "Body", "车身"),
        "Trunk" to Triple("Nadwozie", "Body", "车身"),
        "ACStatus" to Triple("Klimatyzacja", "Climate", "空调"),
        "ACCirc" to Triple("Klimatyzacja", "Climate", "空调"),
        "ACTemp" to Triple("Klimatyzacja", "Climate", "空调"),
        "FanLevel" to Triple("Klimatyzacja", "Climate", "空调"),
        "SeatbeltFL" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "SeatbeltFR" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "SeatbeltRL" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "SeatbeltRM" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "SeatbeltRR" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "OccupancyFL" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "OccupancyFR" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "OccupancyRL" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "OccupancyRM" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "OccupancyRR" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "KeyBattery" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "LightLevel" to Triple("Światła", "Light", "灯光"),
        "TirePressFL" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "TirePressFR" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "TirePressRL" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "TirePressRR" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "Rain" to Triple("Bezpieczeństwo", "Safety", "安全"),
        "LightLow" to Triple("Światła", "Light", "灯光"),
        "DRL" to Triple("Światła", "Light", "灯光"),
    )
    private val parUnitPl = mapOf(
        "Speed" to "km/h",
        "Gear" to "",
        "DriveMode" to "",
        "TurnSignal" to "",
        "SOC" to "%",
        "ChargingStatus" to "",
        "Voltage12V" to "V",
        "MinCellVoltage" to "V",
        "MaxCellVoltage" to "V",
        "RangeKm" to "km",
        "ExtTemp" to "°C",
        "InsideTemp" to "°C",
        "AvgBatTemp" to "°C",
        "WindowFL" to "% otw.",
        "WindowFR" to "% otw.",
        "WindowRL" to "% otw.",
        "WindowRR" to "% otw.",
        "Sunroof" to "% otw.",
        "DoorFL" to "",
        "DoorFR" to "",
        "DoorRL" to "",
        "DoorRR" to "",
        "Hood" to "",
        "LockFL" to "",
        "Trunk" to "",
        "ACStatus" to "",
        "ACCirc" to "",
        "ACTemp" to "°C",
        "FanLevel" to "",
        "SeatbeltFL" to "",
        "TirePressFL" to "kPa",
        "TirePressFR" to "kPa",
        "TirePressRL" to "kPa",
        "TirePressRR" to "kPa",
        "Rain" to "(0=sucho)",
        "SeatbeltFR" to "",
        "SeatbeltRL" to "",
        "SeatbeltRM" to "",
        "SeatbeltRR" to "",
        "OccupancyFL" to "",
        "OccupancyFR" to "",
        "OccupancyRL" to "",
        "OccupancyRM" to "",
        "OccupancyRR" to "",
        "KeyBattery" to "(0=w normie)",
        "LightLevel" to "(1=ciemno, 5=jasno)",
        "LightLow" to "",
        "DRL" to "",
    )
    // param -> list of (value, pl-label)
    private val parEnumPl = mapOf<String, List<Pair<String, String>>>(
        "Gear" to listOf("1" to "P", "2" to "R", "3" to "N", "4" to "D"),
        // Live codes (Leopard 3 2026-07-30): 1=ECO, 2=SPORT, 3=NORMAL, 4=SNOW (ENERGY_OPERATION_SNOW,
        // live 2026-09-27; sand/mud/mountain leave it at 3); 0 is the transient switching state
        // and is not offered as a choice.
        "DriveMode" to listOf(
            "1" to "ECO", "2" to "SPORT", "3" to "NORMAL", "4" to "Śnieg",
            "5" to "Piasek", "6" to "Błoto", "7" to "Góry", "21" to "Inteligentny",
        ),
        // Live mask (Leopard 3 2026-07-31): 1=off, 2=left, 4=right, 6=hazard
        "TurnSignal" to listOf("1" to "Wyłączony", "2" to "Lewy", "4" to "Prawy", "6" to "Awaryjne"),
        "ChargingStatus" to listOf("0" to "Brak", "1" to "Podłączony", "2" to "Ładuje się"),
        "DoorFL" to listOf("0" to "Zamknięte", "1" to "Otwarte"),
        "DoorFR" to listOf("0" to "Zamknięte", "1" to "Otwarte"),
        "DoorRL" to listOf("0" to "Zamknięte", "1" to "Otwarte"),
        "DoorRR" to listOf("0" to "Zamknięte", "1" to "Otwarte"),
        "Hood" to listOf("0" to "Zamknięty", "1" to "Otwarty"),
        "LockFL" to listOf("1" to "Odblokowany", "2" to "Zablokowany"),
        "Trunk" to listOf("2" to "Zamknięty", "1" to "Otwarty", "3" to "W ruchu"),
        "ACCirc" to listOf("0" to "Powietrze z zewnątrz", "1" to "Powietrze z wnętrza"),
        "SeatbeltFL" to listOf("0" to "Niezapięty", "1" to "Zapięty"),
        "SeatbeltFR" to listOf("0" to "Niezapięty", "1" to "Zapięty"),
        "SeatbeltRL" to listOf("0" to "Niezapięty", "1" to "Zapięty"),
        "SeatbeltRM" to listOf("0" to "Niezapięty", "1" to "Zapięty"),
        "SeatbeltRR" to listOf("0" to "Niezapięty", "1" to "Zapięty"),
        "OccupancyFL" to listOf("1" to "Wolny", "2" to "Zajęty"),
        "OccupancyFR" to listOf("1" to "Wolny", "2" to "Zajęty"),
        "OccupancyRL" to listOf("1" to "Wolny", "2" to "Zajęty"),
        "OccupancyRM" to listOf("1" to "Wolny", "2" to "Zajęty"),
        "OccupancyRR" to listOf("1" to "Wolny", "2" to "Zajęty"),
        "LightLow" to listOf("0" to "Wył.", "1" to "Wł."),
        "DRL" to listOf("0" to "Brak", "1" to "Wł.", "2" to "Wył."),
    )

    @Test fun action_names_and_categories_match_baseline_all_locales() {
// The “… toggle” entries carry no command of their own; their names are built
        // from the toggle-target baseline and checked in toggle_catalog_entries_*.
        val commandOptions = ACTION_COMMANDS.filter { it.toggleTarget == null }
        for (a in commandOptions) {
            val n = actName.getValue(a.command); val c = actCat.getValue(a.command)
            lang("pl"); assertEquals("name pl ${a.command}", n.first,  a.localizedName(ctx)); assertEquals("cat pl ${a.command}", c.first,  a.localizedCategory(ctx))
            lang("en"); assertEquals("name en ${a.command}", n.second, a.localizedName(ctx)); assertEquals("cat en ${a.command}", c.second, a.localizedCategory(ctx))
            lang("zh"); assertEquals("name zh ${a.command}", n.third,  a.localizedName(ctx)); assertEquals("cat zh ${a.command}", c.third,  a.localizedCategory(ctx))
        }
        assertEquals(actName.size, commandOptions.size)
    }

    @Test fun param_names_and_categories_match_baseline_all_locales() {
        for (t in TRIGGER_PARAMS) {
            val n = parName.getValue(t.param); val c = parCat.getValue(t.param)
            lang("pl"); assertEquals("pname pl ${t.param}", n.first,  t.localizedName(ctx)); assertEquals("pcat pl ${t.param}", c.first,  t.localizedCategory(ctx))
            lang("en"); assertEquals("pname en ${t.param}", n.second, t.localizedName(ctx)); assertEquals("pcat en ${t.param}", c.second, t.localizedCategory(ctx))
            lang("zh"); assertEquals("pname zh ${t.param}", n.third,  t.localizedName(ctx)); assertEquals("pcat zh ${t.param}", c.third,  t.localizedCategory(ctx))
        }
        assertEquals(parName.size, TRIGGER_PARAMS.size)
    }

    // Toggle targets are picked in the editor and printed in the failure reason,
    // so their names must exist in every locale the catalog is checked against.
    private val toggleTargetName = mapOf(
        "trunk" to Triple("Bagażnik", "Trunk", "后备箱"),
        "front_trunk" to Triple("Przedni bagażnik", "Front trunk", "前备箱"),
        "sunroof" to Triple("Szyberdach", "Sunroof", "天窗"),
        "locks" to Triple("Zamki drzwi", "Door locks", "车门锁"),
        "cluster" to Triple("Wyświetlanie na wskaźnikach", "Projection to cluster", "投射到仪表盘"),
        "sentry" to Triple("Tryb ochrony", "Sentry mode", "哨兵模式"),
        "hazard" to Triple("Światła awaryjne", "Hazard lights", "双闪"),
        "climate" to Triple("Klimatyzacja", "Climate", "空调"),
        "seat_heat_driver" to Triple("Podgrzewanie fotela kierowcy", "Driver seat heating", "主驾座椅加热"),
        "seat_heat_passenger" to Triple("Podgrzewanie fotela pasażera", "Passenger seat heating", "副驾座椅加热"),
        "seat_vent_driver" to Triple("Wentylacja fotela kierowcy", "Driver seat ventilation", "主驾座椅通风"),
        "seat_vent_passenger" to Triple("Wentylacja fotela pasażera", "Passenger seat ventilation", "副驾座椅通风"),
        "steering_heat" to Triple("Podgrzewanie kierownicy", "Steering wheel heating", "方向盘加热"),
    )

    private fun toggleName(target: String): String {
        val res = ActionDispatcher.toggleTargetNameRes(target)!!
        return ctx.appLocalizedContext().getString(res)
    }

    @Test fun toggle_target_names_match_baseline_all_locales() {
        for (target in ActionDispatcher.TOGGLE_TARGETS) {
            val n = toggleTargetName.getValue(target)
            lang("pl"); assertEquals("toggle pl $target", n.first, toggleName(target))
            lang("en"); assertEquals("toggle en $target", n.second, toggleName(target))
            lang("zh"); assertEquals("toggle zh $target", n.third, toggleName(target))
        }
        assertEquals(toggleTargetName.size, ActionDispatcher.TOGGLE_TARGETS.size)
    }

    @Test fun toggle_catalog_entries_read_as_the_target_plus_toggle() {
        val format = Triple("%1${'$'}s: przełącz", "%s: toggle", "%s：切换")
        for (option in ACTION_COMMANDS.filter { it.toggleTarget != null }) {
            val n = toggleTargetName.getValue(option.toggleTarget!!)
            lang("pl"); assertEquals(format.first.format(n.first), option.localizedName(ctx))
            lang("en"); assertEquals(format.second.format(n.second), option.localizedName(ctx))
            lang("zh"); assertEquals(format.third.format(n.third), option.localizedName(ctx))
        }
    }

    @Test fun param_units_and_enum_labels_match_baseline_pl() {
        lang("pl")
        for (t in TRIGGER_PARAMS) {
            assertEquals("unit pl ${t.param}", parUnitPl.getValue(t.param), t.localizedUnit(ctx))
            val enums = parEnumPl[t.param] ?: emptyList()
            for ((value, plLabel) in enums) {
                assertEquals("enum pl ${t.param}=$value", plLabel, t.localizedEnumLabel(value, ctx))
            }
        }
    }
}
