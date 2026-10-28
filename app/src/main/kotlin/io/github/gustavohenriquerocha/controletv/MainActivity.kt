package io.github.gustavohenriquerocha.controletv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.gustavohenriquerocha.controletv.ui.TelaControle

class MainActivity : ComponentActivity() {
    private val vm: ControleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vermelho = Color(0xFFC0392B)
            val cores = if (isSystemInDarkTheme()) darkColorScheme(primary = vermelho)
            else lightColorScheme(primary = vermelho)
            MaterialTheme(colorScheme = cores) {
                TelaControle(vm)
            }
        }
    }
}
