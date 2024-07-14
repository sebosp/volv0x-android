/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.volv0x

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.volv0x.server.GATTServerSampleService.Companion.CHARACTERISTIC_UUID
import com.example.volv0x.server.GATTServerSampleService.Companion.SERVICE_UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.random.Random


@OptIn(ExperimentalAnimationApi::class)
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.M)

@Composable
fun ConnectGATTSample() {
    var selectedDevice by remember {
        mutableStateOf<BluetoothDevice?>(null)
    }
    // Check that BT permissions and that BT is available and enabled
    BluetoothSampleBox {
        AnimatedContent(targetState = selectedDevice, label = "Selected device") { device ->
            if (device == null) {
                // Scans for BT devices and handles clicks (see FindDeviceSample)
                FindDevicesScreen {
                    selectedDevice = it
                }
            } else {
                // Once a device is selected show the UI and try to connect device
                ConnectDeviceScreen(device = device) {
                    selectedDevice = null
                }
            }
        }
    }
}

@SuppressLint("InlinedApi")
@RequiresPermission(anyOf = [
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.VIBRATE,
    Manifest.permission.POST_NOTIFICATIONS,
    ]
)
@Composable
fun ConnectDeviceScreen(device: BluetoothDevice, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    // Keeps track of the last connection state with the device
    var state by remember(device) {
        mutableStateOf<DeviceConnectionState?>(null)
    }

    // Once the device services are discovered find the GATTServerSample service
    val service by remember(state?.services) {

        val service = state?.services?.find {            it.uuid == SERVICE_UUID        }

        if (service != null){
            val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic != null && state?.isNotificationSetup != true) {
                state?.gatt?.setCharacteristicNotification(characteristic, true)
                for (descriptor in characteristic.descriptors) {
                        Log.i("ConnectDeviceScreen", "Writing descriptor: $descriptor")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        state?.gatt?.writeDescriptor(descriptor)
                        state?.isNotificationSetup = true
                }
            }
        }

        mutableStateOf(service)
    }
    // If the GATTServerSample service is found, get the characteristic
    val characteristic by remember(service) {
        mutableStateOf(service?.getCharacteristic(CHARACTERISTIC_UUID))
    }

    // This effect will handle the connection and notify when the state changes
    BLEConnectEffect(device = device) {
        // update our state to recompose the UI
        Log.i("ConnectDeviceScreen", "State changed: $it")
        state = it
    }
    // The message received should be a u16, we need to represent it as string
    var distanceRangeTextColor = MaterialTheme.colorScheme.primary;
    var distanceSensed = 10000 // Out of range
    if (state != null) {
        distanceSensed = state!!.distanceSensed
    }
    var notificationColor = Color.BLACK
    var importance = NotificationManager.IMPORTANCE_NONE
    if (distanceSensed < 400) {
        importance = NotificationManager.IMPORTANCE_HIGH
        notificationColor = Color.RED
        distanceRangeTextColor = MaterialTheme.colorScheme.error
    } else if (distanceSensed < 800) {
        importance = NotificationManager.IMPORTANCE_DEFAULT
        notificationColor = Color.MAGENTA
        distanceRangeTextColor = MaterialTheme.colorScheme.primary
    } else if (distanceSensed < 1200) {
        importance = NotificationManager.IMPORTANCE_LOW
        distanceRangeTextColor = MaterialTheme.colorScheme.secondary
    } else  if (distanceSensed < 1600){
        distanceRangeTextColor = MaterialTheme.colorScheme.tertiary
        importance = NotificationManager.IMPORTANCE_MIN
    }
    val mChannel = NotificationChannel("SEB-PATCH-DISTANCE", "PATCH DST", importance)
    mChannel.setShowBadge(true)
    mChannel.enableLights(true)
    mChannel.enableVibration(true)
    mChannel.setLightColor(notificationColor)

    val notificationManager =  LocalContext.current.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(mChannel)
    if (distanceSensed > 0 && distanceSensed < 800) {



        val notificationBuilder =
            NotificationCompat.Builder(LocalContext.current, "SEB-PATCH-DISTANCE")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("DIST: $distanceSensed mm")
                .setContentText("$distanceSensed mm object too close")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)


        notificationManager.notify(666, notificationBuilder.build())

    } else {
        notificationManager.cancelAll()
    }
        if (distanceSensed > 800) {

        } else if (distanceSensed > 600) {
            distanceRangeTextColor = MaterialTheme.colorScheme.primary
        } else {
//            Log.e("ConnectDeviceScreen", "Vibrating!")
//            // Instantiate a vibrator:
//            val vibratorManager = LocalContext.current.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
//            val vibrator = vibratorManager.defaultVibrator
//            val vibratorEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
//            vibrator.vibrate(vibratorEffect)
            distanceRangeTextColor = MaterialTheme.colorScheme.error
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Devices details", style = MaterialTheme.typography.headlineSmall)
        Text(text = "Name: ${device.name} (${device.address})")
        Text(text = "Status: ${state?.connectionState?.toConnectionStateString()}")
        Text(text = "MTU: ${state?.mtu}")
        Text(text = "Services: ${state?.services?.joinToString { it.uuid.toString() + " " + it.type }}")
        Text(text = "Message sent: ${state?.messageSent}")
        Text(
            text = "DST: ${state?.distanceSensed} mm",
            color = distanceRangeTextColor,
            fontSize = MaterialTheme.typography.displayLarge.fontSize,
        )
        Text(
            text = "Battery reading on A2 pin: ${state?.batteryLevel} mV",
        )
        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    if (state?.connectionState == BluetoothProfile.STATE_DISCONNECTED) {
                        state?.gatt?.connect()
                    }
                    // Example on how to request specific MTUs
                    // Note that from Android 14 onwards the system will define a default MTU and
                    // it will only be sent once to the peripheral device
                    state?.gatt?.requestMtu(Random.nextInt(27, 190))
                }
            }
        ) {
            Text(text = "Request MTU")
        }
        Button(
            enabled = state?.gatt != null,
            onClick = {
                scope.launch(Dispatchers.IO) {
                    // Once we have the connection discover the peripheral services
                    state?.gatt?.discoverServices()
                }
            },
        ) {
            Text(text = "Discover")
        }
        Button(
            enabled = state?.gatt != null && characteristic != null,
            onClick = {
                scope.launch(Dispatchers.IO) {
                    sendData(state?.gatt!!, characteristic!!)
                }
            },
        ) {
            Text(text = "Write to server")
        }
        Button(
            enabled = state?.gatt != null && characteristic != null,
            onClick = {
                scope.launch(Dispatchers.IO) {
                    state?.gatt?.readCharacteristic(characteristic)
                }
            },
        ) {
            Text(text = "Read characteristic")
        }

        Button(onClick = onClose) {
            Text(text = "Close")
        }
    }
}

/**
 * Writes "hello world" to the server characteristic
 */
@SuppressLint("MissingPermission")
private fun sendData(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
) {
    val data = "Hello Patch!".toByteArray()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(
            characteristic,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
    } else {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        characteristic.value = data
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(characteristic)
    }
}

internal fun Int.toConnectionStateString() = when (this) {
    BluetoothProfile.STATE_CONNECTED -> "Connected"
    BluetoothProfile.STATE_CONNECTING -> "Connecting"
    BluetoothProfile.STATE_DISCONNECTED -> "Disconnected"
    BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting"
    else -> "N/A"
}

private data class DeviceConnectionState(
    val gatt: BluetoothGatt?,
    val connectionState: Int,
    val mtu: Int,
    val services: List<BluetoothGattService> = emptyList(),
    val messageSent: Boolean = false,
    val distanceSensed:Int = 0,
    val batteryLevel:Int = 0,
    var isNotificationSetup: Boolean = false,
    var isVibrationEnabled: Boolean = true,
) {
    companion object {
        val None = DeviceConnectionState(null, -1, -1)
    }
}

@SuppressLint("InlinedApi")
@RequiresPermission(anyOf = [
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.VIBRATE,
    Manifest.permission.POST_NOTIFICATIONS,
    ]
)
@Composable
private fun BLEConnectEffect(
    device: BluetoothDevice,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onStateChange: (DeviceConnectionState) -> Unit,
) {
    val context = LocalContext.current
    val currentOnStateChange by rememberUpdatedState(onStateChange)

    // Keep the current connection state
    var state by remember {
        mutableStateOf(DeviceConnectionState.None)
    }

    DisposableEffect(lifecycleOwner, device) {
        // This callback will notify us when things change in the GATT connection so we can update
        // our state
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                super.onConnectionStateChange(gatt, status, newState)
                state = state.copy(gatt = gatt, connectionState = newState)
                currentOnStateChange(state)

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Here you should handle the error returned in status based on the constants
                    // https://developer.android.com/reference/android/bluetooth/BluetoothGatt#summary
                    // For example for GATT_INSUFFICIENT_ENCRYPTION or
                    // GATT_INSUFFICIENT_AUTHENTICATION you should create a bond.
                    // https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createBond()
                    Log.e("BLEConnectEffect", "An error happened: $status")
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                state = state.copy(gatt = gatt, mtu = mtu)
                currentOnStateChange(state)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                state = state.copy(services = gatt.services)
                currentOnStateChange(state)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int,
            ) {
                super.onCharacteristicWrite(gatt, characteristic, status)
                state = state.copy(messageSent = status == BluetoothGatt.GATT_SUCCESS)
                currentOnStateChange(state)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                super.onCharacteristicRead(gatt, characteristic, status)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    doOnRead(characteristic.value)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                super.onCharacteristicRead(gatt, characteristic, value, status)
                doOnRead(value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                super.onCharacteristicChanged(gatt, characteristic, value)
                doOnRead(value)
            }

            private fun doOnRead(value: ByteArray) {
                val buffer = ByteBuffer.wrap(value)
                val distanceSensed =  buffer.short.toInt()
                val batteryLevel = buffer.short.toInt()
                state = state.copy(distanceSensed = distanceSensed)
                state = state.copy(batteryLevel = batteryLevel)
//                    Log.e("ConnectDeviceScreen", "Vibrating doOnRead!")
//                    // Instantiate a vibrator:
//                    val vibratorManager =
//                        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
//                    val vibrator = vibratorManager.defaultVibrator
//                    val vibratorEffect =
//                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
//                    vibrator.vibrate(vibratorEffect)

                currentOnStateChange(state)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (state.gatt != null) {
                    // If we previously had a GATT connection let's reestablish it
                    state.gatt?.connect()
                } else {
                    // Otherwise create a new GATT connection
                    state = state.copy(gatt = device.connectGatt(context, false, callback))
                }
            } else if (event == Lifecycle.Event.ON_STOP) {
                // Unless you have a reason to keep connected while in the bg you should disconnect
                state.gatt?.disconnect()
            }
        }

        // Add the observer to the lifecycle
        lifecycleOwner.lifecycle.addObserver(observer)

        // When the effect leaves the Composition, remove the observer and close the connection
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.gatt?.close()
            state = DeviceConnectionState.None
        }
    }
}
