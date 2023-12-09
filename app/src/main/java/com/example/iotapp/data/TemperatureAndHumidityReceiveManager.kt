package com.example.iotapp.data

import com.example.iotapp.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow

interface TemperatureAndHumidityReceiveManager {

    val data: MutableSharedFlow<Resource<TempHumidityResult>>

    fun reconnect()

    fun disconnect()

    fun startReceiving()

    fun closeConnection()

}