package com.example

import android.app.Application
import com.example.data.ManhwaDatabase
import com.example.data.ManhwaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class ManhwaApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob())

    val database by lazy { ManhwaDatabase.getDatabase(this, applicationScope) }
    val repository by lazy { ManhwaRepository(this, database.manhwaDao()) }
}
