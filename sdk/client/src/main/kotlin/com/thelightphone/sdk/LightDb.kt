package com.thelightphone.sdk

import androidx.room.Room
import androidx.room.RoomDatabase

data class LightRoomDbConfig<T : RoomDatabase>(val dbClass: Class<T>, val dbName: String?)

fun <T : RoomDatabase> SimpleLightScreen<*>.buildDatabase(config: LightRoomDbConfig<T>): T {
    return Room.databaseBuilder(activity.applicationContext, config.dbClass, config.dbName).build()
}