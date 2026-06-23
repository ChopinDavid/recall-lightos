package com.thelightphone.sdk

import androidx.room.Room
import androidx.room.RoomDatabase

fun <T : RoomDatabase> SimpleLightScreen<*>.buildDatabase(dbClass: Class<T>, dbName: String?): T {
    return Room.databaseBuilder(activity.applicationContext, dbClass, dbName).build()
}