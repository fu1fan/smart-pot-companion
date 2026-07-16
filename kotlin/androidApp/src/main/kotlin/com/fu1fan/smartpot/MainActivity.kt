package com.fu1fan.smartpot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fu1fan.smartpot.ui.SmartPotApp
import com.fu1fan.smartpot.ui.SmartPotViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<SmartPotViewModel> { SmartPotViewModel.Factory }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val notified = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    state.snapshot?.activeAlerts?.filter { notified.add(it.id) }?.forEach { alert ->
                        getSystemService(NotificationManager::class.java).notify(
                            alert.id.hashCode(),
                            NotificationCompat.Builder(this@MainActivity, "plant_alerts")
                                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                                .setContentTitle(state.snapshot.pot.displayName)
                                .setContentText(alert.message)
                                .setPriority(NotificationCompat.PRIORITY_HIGH)
                                .setAutoCancel(true)
                                .build(),
                        )
                    }
                }
            }
        }
        setContent { SmartPotApp(viewModel) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("plant_alerts", "绿植异常提醒", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }
}
