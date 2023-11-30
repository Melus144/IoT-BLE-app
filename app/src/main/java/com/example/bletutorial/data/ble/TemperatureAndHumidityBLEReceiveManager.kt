package com.example.bletutorial.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.bletutorial.data.ConnectionState
import com.example.bletutorial.data.TempHumidityResult
import com.example.bletutorial.data.TemperatureAndHumidityReceiveManager
import com.example.bletutorial.util.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@SuppressLint("MissingPermission")
class TemperatureAndHumidityBLEReceiveManager @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) : TemperatureAndHumidityReceiveManager {

    //Remove these old variables
    private val TEMP_HUMIDITY_CHARACTERISTICS_UUID = "0000aa21-0000-1000-8000-00805f9b34fb"

    //Device name, service uuid, characteristics uuid
    private val DEVICE_NAME = "Grup5"
    private val DEVICE_SERVICE_UUID = UUID.fromString("19b10010-e8f2-537e-4f6c-d104768a1214")
    private val TEMPERATURE_CHARACTERISTIC_UUID = UUID.fromString("19b10011-e8f2-537e-4f6c-d104768a1214")
    private val HUMIDITY_CHARACTERISTIC_UUID = UUID.fromString("19b10012-e8f2-537e-4f6c-d104768a1214")

    // Variables temporales
    private var tempValue: Float? = null
    private var humidityValue: Float? = null

    override val data: MutableSharedFlow<Resource<TempHumidityResult>> = MutableSharedFlow()

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var gatt: BluetoothGatt? = null

    private var isScanning = false

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val scanCallback = object : ScanCallback(){

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if(result.device.name == DEVICE_NAME){
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Connecting to device..."))
                }
                if(isScanning){
                    result.device.connectGatt(context,false, gattCallback)
                    isScanning = false
                    bleScanner.stopScan(this)
                }
            }
        }
    }

    private var currentConnectionAttempt = 1
    private var MAXIMUM_CONNECTION_ATTEMPTS = 5

    private val gattCallback = object : BluetoothGattCallback(){
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if(status == BluetoothGatt.GATT_SUCCESS){
                if(newState == BluetoothProfile.STATE_CONNECTED){
                    coroutineScope.launch {
                        data.emit(Resource.Loading(message = "Discovering Services..."))
                    }
                    gatt.discoverServices()
                    this@TemperatureAndHumidityBLEReceiveManager.gatt = gatt
                } else if(newState == BluetoothProfile.STATE_DISCONNECTED){
                    coroutineScope.launch {
                        data.emit(Resource.Success(data = TempHumidityResult(0f,0f,ConnectionState.Disconnected)))
                    }
                    gatt.close()
                }
            }else{
                gatt.close()
                currentConnectionAttempt+=1
                coroutineScope.launch {
                    data.emit(
                        Resource.Loading(
                            message = "Attempting to connect $currentConnectionAttempt/$MAXIMUM_CONNECTION_ATTEMPTS"
                        )
                    )
                }
                if(currentConnectionAttempt<=MAXIMUM_CONNECTION_ATTEMPTS){
                    startReceiving()
                }else{
                    coroutineScope.launch {
                        data.emit(Resource.Error(errorMessage = "Could not connect to ble device"))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt){
                printGattTable()
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space..."))
                }
                gatt.requestMtu(517)
            }
            // After discovering services, attempt to find the characteristics
            val temperatureCharacteristic = findCharacteristics(DEVICE_SERVICE_UUID.toString(), TEMPERATURE_CHARACTERISTIC_UUID.toString())
            val humidityCharacteristic = findCharacteristics(DEVICE_SERVICE_UUID.toString(), HUMIDITY_CHARACTERISTIC_UUID.toString())
            if (temperatureCharacteristic != null && humidityCharacteristic != null) {
                // If both characteristics are found, enable notifications on them
                enableNotification(temperatureCharacteristic)
                enableNotification(humidityCharacteristic)
            } else {
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find temperature and/or humidity characteristics"))
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val temperatureCharacteristic = findCharacteristics(DEVICE_SERVICE_UUID.toString(), TEMPERATURE_CHARACTERISTIC_UUID.toString())
            val humidityCharacteristic = findCharacteristics(DEVICE_SERVICE_UUID.toString(), HUMIDITY_CHARACTERISTIC_UUID.toString())
            if(temperatureCharacteristic == null){
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find temp publisher"))
                }
                return
            }
            if(humidityCharacteristic == null){
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find humidity publisher"))
                }
                return
            }
            // If both characteristics are found, enable notifications on them
            enableNotification(temperatureCharacteristic)
            enableNotification(humidityCharacteristic)
            }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
                when(characteristic.uuid) {
                    TEMPERATURE_CHARACTERISTIC_UUID -> {
                        tempValue = parseTemperature(characteristic.value)
                    }

                    HUMIDITY_CHARACTERISTIC_UUID -> {
                        humidityValue = parseHumidity(characteristic.value)
                    }
                }
            // Comprobar si ambos valores están disponibles
            if (tempValue != null && humidityValue != null) {
                val tempHumidityResult = TempHumidityResult(
                    tempValue!!,
                    humidityValue!!,
                    ConnectionState.Connected
                )
                coroutineScope.launch {
                    data.emit(Resource.Success(data = tempHumidityResult))
                }
                // Resetear los valores para futuras actualizaciones
                tempValue = null
                humidityValue = null
             }
        }

        /*
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    with(characteristic){
                        when(uuid){
                            UUID.fromString(TEMP_HUMIDITY_CHARACTERISTICS_UUID) -> {
                                //XX XX XX XX XX XX
                                val multiplicator = if(value.first().toInt()> 0) -1 else 1
                                val temperature = value[1].toInt() + value[2].toInt() / 10f
                                val humidity = value[4].toInt() + value[5].toInt() / 10f
                                val tempHumidityResult = TempHumidityResult(
                                    multiplicator * temperature,
                                    humidity,
                                    ConnectionState.Connected
                                )
                                coroutineScope.launch {
                                    data.emit(
                                        Resource.Success(data = tempHumidityResult)
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                */

    }

    private fun parseHumidity(value: ByteArray): Float {
        //TODO: lets try first byte humidity
        return value[0].toFloat()
    }

    private fun parseTemperature(value: ByteArray): Float {
        return value[1].toFloat()
    }

    private fun enableNotification(characteristic: BluetoothGattCharacteristic){
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        val payload = when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> return
        }

        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if(gatt?.setCharacteristicNotification(characteristic, true) == false){
                Log.d("BLEReceiveManager","set characteristics notification failed")
                return
            }
            writeDescription(cccdDescriptor, payload)
        }
    }

    private fun writeDescription(descriptor: BluetoothGattDescriptor, payload: ByteArray){
        gatt?.let { gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
        } ?: error("Not connected to a BLE device!")
    }

    private fun findCharacteristics(serviceUUID: String, characteristicsUUID:String):BluetoothGattCharacteristic?{
        return gatt?.services?.find { service ->
            service.uuid.toString() == serviceUUID
        }?.characteristics?.find { characteristics ->
            characteristics.uuid.toString() == characteristicsUUID
        }
    }

    override fun startReceiving() {
        coroutineScope.launch {
            data.emit(Resource.Loading(message = "Scanning Ble devices..."))
        }
        isScanning = true
        bleScanner.startScan(null,scanSettings,scanCallback)
    }

    override fun reconnect() {
        gatt?.connect()
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun closeConnection() {
        bleScanner.stopScan(scanCallback)

        val humidityCharacteristic = findCharacteristics(DEVICE_SERVICE_UUID.toString(), HUMIDITY_CHARACTERISTIC_UUID.toString())
        val temperatureCharacteristic = findCharacteristics(DEVICE_SERVICE_UUID.toString(), TEMPERATURE_CHARACTERISTIC_UUID.toString())

        if(humidityCharacteristic != null){
            disconnectCharacteristic(humidityCharacteristic)
        }
        if(temperatureCharacteristic != null){
            disconnectCharacteristic(temperatureCharacteristic)
        }
        gatt?.close()
    }

    private fun disconnectCharacteristic(characteristic: BluetoothGattCharacteristic){
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if(gatt?.setCharacteristicNotification(characteristic,false) == false){
                Log.d("TempHumidReceiveManager","set charateristics notification failed")
                return
            }
            writeDescription(cccdDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }

}