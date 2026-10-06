package com.ynk.virtualdisplay.automation

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Small atomic private checkpoint. It is evidence, not permission to replay an action. */
class FileCheckpointStore(file: File): CheckpointStore {
    private val atomic=AtomicFile(file)
    init {file.parentFile?.mkdirs()}
    override fun save(checkpoint: SessionCheckpoint) {
        val value=mapOf("sessionId" to checkpoint.sessionId,"round" to checkpoint.round,"phase" to checkpoint.phase,
            "trustedPage" to checkpoint.trustedPage,"health" to checkpoint.health?.fields(),
            "observedAt" to checkpoint.observedAt,"healthRequired" to checkpoint.healthRequired,"actionPending" to checkpoint.actionPending)
        val stream=atomic.startWrite()
        try {stream.write(EventJson.encode(value).toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
        catch(t: Exception) {atomic.failWrite(stream);throw t}
    }
    override fun load(): SessionCheckpoint? {
        if(!atomic.baseFile.exists()) return null
        val json=JSONObject(atomic.readFully().toString(Charsets.UTF_8))
        val health=json.optJSONObject("health")?.let {h ->
            fun observed(j: JSONObject): ObservedValue<Int> = when(j.getString("status")) {
                "KNOWN" -> ObservedValue.Known(j.getInt("value"),j.getLong("observedAt"),j.getDouble("confidence"),
                    j.getString("evidence"),ObservationSource.valueOf(j.getString("source")))
                "UNKNOWN" -> ObservedValue.Unknown(j.getString("reason"),j.getLong("attemptedAt"),j.getString("evidence"))
                "UNSUPPORTED" -> ObservedValue.Unsupported(j.getString("reason"))
                "STALE" -> {val previous=j.getJSONObject("previous");ObservedValue.Stale(
                    ObservedValue.Known(previous.getInt("value"),previous.getLong("observedAt"),previous.getDouble("confidence"),
                        previous.getString("evidence"),ObservationSource.valueOf(previous.getString("source"))),
                    j.getLong("checkedAt"),j.getString("reason"))}
                else -> error("CHECKPOINT_OBSERVATION_INVALID")
            }
            val bar=h.getJSONObject("barEstimate")
            HealthObservations(observed(h.getJSONObject("energy")),observed(h.getJSONObject("cleanliness")),
                observed(h.getJSONObject("mood")),observed(bar.getJSONObject("energy")),
                observed(bar.getJSONObject("cleanliness")),observed(bar.getJSONObject("mood")),h.getBoolean("barReady"),h.getString("evidence"))
        }
        return SessionCheckpoint(json.getString("sessionId"),json.getInt("round"),json.getString("phase"),
            json.getString("trustedPage"),health,json.getLong("observedAt"),json.getBoolean("healthRequired"),json.getBoolean("actionPending"))
    }
}
