package com.happypipaliya.sleepcore

/**
 * Plain-text encoding for what the phone and watch send each other. Kept dependency-free so both
 * sides (and the unit tests) read and write exactly the same format.
 */
object Codec {
    fun encode(s: AlarmSettings) = listOf(if (s.enabled) 1 else 0, s.startMinute, s.windowMinutes, s.days).joinToString(",")

    fun decodeSettings(text: String?): AlarmSettings {
        val p = text?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        if (p == null || p.size < 4) return AlarmSettings()
        return AlarmSettings(p[0] == 1, p[1], p[2], p[3])
    }

    fun encode(m: Model): String = listOf(
        m.version.toString(),
        m.means.joinToString(","),
        m.sds.joinToString(","),
        m.weights.joinToString(","),
        m.bias.toString(),
        m.threshold.toString(),
        m.minConsecutive.toString(),
        m.wakeStages.joinToString(",") { it.name },
        m.trainedNights.toString(),
        m.accuracy?.toString().orEmpty(),
    ).joinToString("|")

    fun decodeModel(text: String?): Model? = runCatching {
        val p = text!!.split('|')
        fun doubles(s: String) = s.split(',').map { it.toDouble() }
        Model(
            version = p[0].toInt(),
            means = doubles(p[1]),
            sds = doubles(p[2]),
            weights = doubles(p[3]),
            bias = p[4].toDouble(),
            threshold = p[5].toDouble(),
            minConsecutive = p[6].toInt(),
            wakeStages = p[7].split(',').filter { it.isNotBlank() }.map { Stage.valueOf(it) }.toSet(),
            trainedNights = p[8].toInt(),
            accuracy = p.getOrNull(9)?.toDoubleOrNull(),
        )
    }.getOrNull()

    fun encode(r: NightRecord): String = buildString {
        append(listOf(r.monitorStart, r.windowStart, r.windowEnd, r.alarmAt, r.trigger, r.grogginess ?: -1, r.modelVersion).joinToString(","))
        r.epochs.forEachIndexed { i, e ->
            append('\n')
            append(listOf(e.start, e.move, e.hr ?: "", e.hrSd ?: "", r.scores.getOrNull(i) ?: "").joinToString(","))
        }
    }

    fun decodeNight(text: String?): NightRecord? = runCatching {
        val lines = text!!.split('\n')
        val h = lines[0].split(',')
        val rows = lines.drop(1).filter { it.isNotBlank() }.map { it.split(',') }
        NightRecord(
            monitorStart = h[0].toLong(),
            windowStart = h[1].toLong(),
            windowEnd = h[2].toLong(),
            alarmAt = h[3].toLong(),
            trigger = h[4],
            grogginess = h[5].toInt().takeIf { it >= 0 },
            modelVersion = h[6].toInt(),
            epochs = rows.map { Epoch(it[0].toLong(), it[1].toDouble(), it[2].toDoubleOrNull(), it[3].toDoubleOrNull()) },
            scores = rows.map { it.getOrNull(4)?.toDoubleOrNull() ?: 0.0 },
        )
    }.getOrNull()

    fun encodeStages(stages: List<Stage>) = stages.joinToString(",") { it.name }

    fun decodeStages(text: String?): List<Stage> =
        text?.split(',')?.filter { it.isNotBlank() }?.map { runCatching { Stage.valueOf(it) }.getOrDefault(Stage.UNKNOWN) }.orEmpty()
}
