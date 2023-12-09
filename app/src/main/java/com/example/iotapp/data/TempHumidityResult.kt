package com.example.iotapp.data

data class TempHumidityResult(
    val temperature:Float,
    val humidity:Float,
    val connectionState: ConnectionState
)
