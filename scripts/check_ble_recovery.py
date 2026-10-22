"""Exercise exact Android notification-drain and framer source without radio access."""
from pathlib import Path
import hashlib, json, os, subprocess, sys, shutil, tomllib

root = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root/'build/ble-recovery-source-check'
out.mkdir(parents=True,exist_ok=True)
source = (root/'app/src/main/kotlin/com/trackspeed/android/sync/BleClockSyncService.kt').read_text()

def block(text, prefix):
    start = text.index(prefix)
    brace = text.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

method = block(source, 'private fun drainServerNotificationQueue()')
queue = next(line.strip() for line in source.splitlines() if 'private val serverNotificationQueue =' in line)
harness = '''package com.trackspeed.android.sync
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

private object Log { fun w(vararg args: Any?) {}; fun e(vararg args: Any?) {} }
private data class Device(val address: String)
private class Scheduled { val callbacks = ArrayDeque<() -> Unit>(); fun postDelayed(f: () -> Unit, delay: Long) { callbacks.add(f) } }
private object State { data class Error(val message: String) }
private class StateBox { var value: State.Error? = null }
private class DrainFixture {
    private val TAG = "fixture"
    private data class ServerPacket(val deviceAddress: String, val data: ByteArray)
    QUEUE
    private val isServerNotificationPending = AtomicBoolean(false)
    private val connectedDevices = mutableMapOf("A" to Device("A"), "B" to Device("B"))
    private val txCharacteristic: Any? = Any()
    private val gattServer: Any? = Any()
    private val handler = Scheduled()
    private val _state = StateBox()
    var rejectCount = 1
    val accepted = mutableListOf<String>()
    var submitCount = 0
    private fun notifyCharacteristicChangedCompat(server: Any, device: Device, characteristic: Any, value: ByteArray): Boolean {
        submitCount++
        if (rejectCount > 0) { rejectCount--; return false }
        accepted.add(value.decodeToString())
        return true
    }
    METHOD
    fun seed() { listOf("A1", "A2", "B1").forEach { serverNotificationQueue.add(ServerPacket(it.take(1), it.toByteArray())) } }
    fun drain() = drainServerNotificationQueue()
    fun retry() { handler.callbacks.removeFirst().invoke() }
    fun callback() { isServerNotificationPending.set(false); drain() }
    fun disconnectA() { connectedDevices.remove("A") }
    val pending: Boolean get() = isServerNotificationPending.get()
    val empty: Boolean get() = serverNotificationQueue.isEmpty()
}
class NotificationDrainSourceTest {
    @Test fun rejectedHeaderIsRetriedBeforeItsContinuationAndOtherMessages() {
        val f=DrainFixture(); f.seed(); f.drain()
        assertTrue(f.accepted.isEmpty()); f.retry()
        assertEquals(listOf("A1"), f.accepted)
        f.drain(); assertEquals(2, f.submitCount) // One rejection, one accepted; callback still pending.
        f.callback(); f.callback(); f.callback()
        assertEquals(listOf("A1", "A2", "B1"), f.accepted)
        assertTrue(f.empty); assertFalse(f.pending)
    }
    @Test fun repeatedSynchronousRejectionKeepsOriginalOrder() {
        val f=DrainFixture(); f.rejectCount=3; f.seed(); f.drain()
        repeat(3) { f.retry() }
        f.callback(); f.callback(); f.callback()
        assertEquals(listOf("A1", "A2", "B1"), f.accepted)
        assertTrue(f.empty); assertFalse(f.pending)
    }
    @Test fun disconnectedPeerPacketsDoNotPreventRemainingRecipientProgress() {
        val f=DrainFixture(); f.rejectCount=0; f.seed(); f.disconnectA(); f.drain()
        assertEquals(listOf("B1"), f.accepted)
        assertTrue(f.pending); f.callback(); assertFalse(f.pending)
    }
}
'''.replace('QUEUE', queue).replace('METHOD', method)
(out/'NotificationDrainSourceTest.kt').write_text(harness)

test_source = (root/'app/src/test/kotlin/com/trackspeed/android/sync/BleMessageFramerTest.kt').read_text()
(out/'BleMessageFramerTest.kt').write_text(test_source)

cache=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
kotlin=tomllib.loads((root/'gradle/libs.versions.toml').read_text())['versions']['kotlin']
def jar(g,a,v): return str(next((cache/g/a/v).glob('*/*.jar')))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib',kotlin)
annotations=jar('org.jetbrains','annotations','13.0')
junit=jar('junit','junit','4.13.2');hamcrest=jar('org.hamcrest','hamcrest-core','1.3')
compiler=[jar('org.jetbrains.kotlin',n,kotlin) for n in ['kotlin-compiler-embeddable','kotlin-script-runtime','kotlin-daemon-embeddable']]
compiler += [stdlib,annotations,jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4')]
framer=root/'app/src/main/kotlin/com/trackspeed/android/sync/BleMessageFramer.kt'
java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
assert java, 'Set JAVA_HOME to the JDK used by Gradle'
classes=out/'classes';classes.mkdir(exist_ok=True)
compiled=subprocess.run([java,'-Xmx256m','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',os.pathsep.join([stdlib,annotations,junit,hamcrest]),'-d',str(classes),str(framer),str(out/'BleMessageFramerTest.kt'),str(out/'NotificationDrainSourceTest.kt')],capture_output=True,text=True)
(out/'compile.log').write_text(compiled.stdout+compiled.stderr)
assert compiled.returncode==0,compiled.stderr
result=subprocess.run([java,'-Xmx128m','-cp',os.pathsep.join([str(classes),stdlib,junit,hamcrest]),'org.junit.runner.JUnitCore','com.trackspeed.android.sync.BleMessageFramerTest','com.trackspeed.android.sync.NotificationDrainSourceTest'],capture_output=True,text=True)
(out/'junit.log').write_text(result.stdout+result.stderr)
record={'exit_code':result.returncode,'framer_sha256':hashlib.sha256(framer.read_bytes()).hexdigest(),'service_method_sha256':hashlib.sha256(method.encode()).hexdigest(),'notification_queue_declaration':queue,'device_used':False,'framework_callbacks_stubbed':True,'output':result.stdout}
(out/'result.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record,indent=2))
raise SystemExit(result.returncode)
