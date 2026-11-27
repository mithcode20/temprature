package com.example.roomtemp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.Gravity
import android.widget.*
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var adapter: BluetoothAdapter
    private lateinit var big: TextView
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var nameFilter: EditText
    private lateinit var offField: EditText
    private lateinit var divField: EditText
    private lateinit var autoPair: CheckBox
    private lateinit var listBox: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val seen = HashSet<String>()
    private val logLines = ArrayDeque<String>()
    private val ops = ArrayDeque<() -> Boolean>()
    private var gatt: BluetoothGatt? = null
    private var pending: BluetoothDevice? = null
    private var scanning = false
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        big = TextView(this).apply { text = "--.- °C"; textSize = 56f; gravity = Gravity.CENTER }
        status = TextView(this).apply { text = "Not connected"; gravity = Gravity.CENTER }
        nameFilter = EditText(this).apply { hint = "Auto-connect name contains (use | for several)"; setText("Therm|TP|THEPRO") }
        autoPair = CheckBox(this).apply { text = "Auto-pair before connecting"; isChecked = true }
        offField = EditText(this).apply { hint = "Temperature byte offset (blank = auto)"; inputType = 2 }
        divField = EditText(this).apply { hint = "Divide by (default 10)"; inputType = 8194 }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; text = "Log" }
        val bar = LinearLayout(this)
        bar.addView(btn("Scan & auto-connect") { startScan() })
        bar.addView(btn("Stop") { stopScan() })
        bar.addView(btn("Disconnect") { gatt?.disconnect() })
        listOf(big, status, nameFilter, autoPair, offField, divField, bar, listBox, logView).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
        registerReceiver(bondRx, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        if (hasPerms()) listBonded() else requestPermissions(needed(), 1)
    }

    override fun onDestroy() { super.onDestroy(); unregisterReceiver(bondRx); gatt?.close() }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
    private fun needed() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasPerms() = needed().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { if (hasPerms()) listBonded() }

    private fun log(s: String) {
        logLines.addFirst(s); while (logLines.size > 40) logLines.removeLast()
        logView.text = logLines.joinToString("\n")
    }

    // ---- find: bonded (paired) devices, PAN check, then BLE scan ----
    private fun listBonded() {
        adapter.bondedDevices?.forEach { d -> if (seen.add(d.address)) addRow(d, d.name, "Paired") }
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                val n = try { proxy.connectedDevices.joinToString { it.name ?: it.address } } catch (e: Exception) { "n/a" }
                ui.post { log("PAN connected devices: ${if (n.isEmpty()) "none" else n}") }
                adapter.closeProfileProxy(p, proxy)
            }
            override fun onServiceDisconnected(p: Int) {}
        }, 5)
    }

    private fun startScan() {
        if (!hasPerms()) { requestPermissions(needed(), 1); return }
        if (!adapter.isEnabled) { Toast.makeText(this, "Turn Bluetooth on", Toast.LENGTH_SHORT).show(); return }
        seen.clear(); listBox.removeAllViews(); listBonded()
        adapter.bluetoothLeScanner.startScan(scanCb); scanning = true
        status.text = "Scanning…"
        ui.postDelayed({ stopScan() }, 30000)
    }
    private fun stopScan() {
        if (scanning) { adapter.bluetoothLeScanner.stopScan(scanCb); scanning = false; if (gatt == null) status.text = "Scan stopped" }
    }
    private fun matches(n: String?) = n != null && nameFilter.text.toString().split("|", ",")
        .map { it.trim() }.filter { it.isNotEmpty() }.any { n.contains(it, true) }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) {
            val d = r.device; val n = r.scanRecord?.deviceName ?: d.name
            if (seen.add(d.address)) addRow(d, n, "signal ${r.rssi}")
            if (gatt == null && pending == null && matches(n)) connect(d)
        }
    }
    private fun addRow(d: BluetoothDevice, n: String?, info: String) =
        listBox.addView(btn("${n ?: "Unnamed"}  ${d.address}\n$info") { connect(d) })

    // ---- pair + connect ----
    private fun connect(d: BluetoothDevice) {
        stopScan()
        if (autoPair.isChecked && d.bondState == BluetoothDevice.BOND_NONE) {
            status.text = "Pairing with ${d.name ?: d.address}… accept any prompt"
            pending = d
            d.createBond()
            ui.postDelayed({ if (pending?.address == d.address) { pending = null; doConnect(d) } }, 12000)
        } else doConnect(d)
    }
    private fun doConnect(d: BluetoothDevice) {
        gatt?.close(); ops.clear()
        status.text = "Connecting to ${d.name ?: d.address}…"
        gatt = d.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
    }
    private val bondRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            if (st == BluetoothDevice.BOND_BONDING) return
            if (pending?.address == d.address) { pending = null; doConnect(d) }
            else if (st == BluetoothDevice.BOND_BONDED && gatt?.device?.address == d.address) gatt?.discoverServices()
        }
    }

    // ---- read everything the device offers ----
    private fun next() { while (true) { val op = synchronized(ops) { ops.poll() } ?: return; if (op()) return } }
    private fun enable(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(cccd) ?: return false
        d.value = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return g.writeDescriptor(d)
    }
    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, n: Int) {
            if (n == BluetoothProfile.STATE_CONNECTED) { ui.post { status.text = "Connected, reading…" }; g.discoverServices() }
            else if (n == BluetoothProfile.STATE_DISCONNECTED) {
                ui.post { status.text = "Disconnected (code $s)" }; g.close(); if (gatt === g) gatt = null
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            for (svc in g.services) for (c in svc.characteristics) {
                val p = c.properties
                if ((p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) ops.add { enable(g, c) }
                if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) ops.add { g.readCharacteristic(c) }
            }
            ui.post { status.text = "Connected: ${g.services.size} services, waiting for data"; log("Services: ${g.services.joinToString { it.uuid.toString().substring(4, 8) }}") }
            next()
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) { next() }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
            if (s == 5 || s == 15) ui.post { g.device.createBond() } else if (s == 0) handle(c, c.value)
            next()
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { handle(c, c.value) }
    }

    private fun handle(c: BluetoothGattCharacteristic, v: ByteArray?) {
        if (v == null || v.isEmpty()) return
        val id = c.uuid.toString().substring(4, 8)
        val hex = v.joinToString(" ") { "%02x".format(it) }
        fun s16(i: Int) = ((v[i].toInt() and 0xff) or (v[i + 1].toInt() shl 8)).toShort().toInt()
        var t: Double? = null; var note = ""
        when (id) {
            "2a6e" -> if (v.size >= 2) t = s16(0) / 100.0
            "2a1c" -> if (v.size >= 5) {
                val m = (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8) or (v[3].toInt() shl 16)
                t = m * Math.pow(10.0, v[4].toInt().toDouble())
                if ((v[0].toInt() and 1) == 1) t = (t - 32) * 5 / 9
            }
            "2a19", "2a00", "2a29", "2a24", "2a25", "2a26", "2a27", "2a28" -> {}
            else -> {
                val off = offField.text.toString().toIntOrNull()
                val div = divField.text.toString().toDoubleOrNull() ?: 10.0
                if (off != null) { if (off + 1 < v.size) t = s16(off) / div }
                else for (i in 0 until v.size - 1) { val x = s16(i) / 10.0; if (x in 5.0..45.0) { t = x; note = "  (auto: byte $i)"; break } }
            }
        }
        ui.post {
            log("$id: $hex$note")
            if (t != null && t > -40 && t < 90) { big.text = "%.1f °C".format(t); status.text = "Live data" }
        }
    }
}
