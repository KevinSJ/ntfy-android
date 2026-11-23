package io.heckel.ntfy.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.AppShortcutManager
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.isDarkThemeOn
import io.heckel.ntfy.util.readBitmapFromUriOrNull
import io.heckel.ntfy.util.subscriptionTopicShortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Activity invoked by launchers via [android.content.Intent.ACTION_CREATE_SHORTCUT]
 * to let the user pick a subscribed topic and place a shortcut to it on the home screen.
 */
class ShortcutActivity : AppCompatActivity() {
    private val repository by lazy { (application as Application).repository }
    private lateinit var appBaseUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcut)

        Log.init(this)
        Log.d(TAG, "Create $this")

        appBaseUrl = getString(R.string.app_base_url)

        val toolbarLayout = findViewById<AppBarLayout>(R.id.app_bar_drawer)
        val dynamicColors = repository.getDynamicColorsEnabled()
        val darkMode = isDarkThemeOn(this)
        val statusBarColor = Colors.statusBarNormal(this, dynamicColors, darkMode)
        val toolbarTextColor = Colors.toolbarTextColor(this, dynamicColors, darkMode)
        toolbarLayout.setBackgroundColor(statusBarColor)

        val toolbar = toolbarLayout.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(toolbarTextColor)
        toolbar.setNavigationIconTint(toolbarTextColor)
        toolbar.overflowIcon?.setTint(toolbarTextColor)
        setSupportActionBar(toolbar)
        title = getString(R.string.shortcut_picker_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            Colors.shouldUseLightStatusBar(dynamicColors, darkMode)

        val listView = findViewById<RecyclerView>(R.id.shortcut_subscriptions_list)
        val emptyView = findViewById<View>(R.id.shortcut_no_subscriptions)
        ViewCompat.setOnApplyWindowInsetsListener(listView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            insets
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val subscriptions = repository.getSubscriptions()
                .filter { it.upAppId == null }
                .sortedWith(
                    compareByDescending<Subscription> { it.lastActive }
                        .thenBy { displayName(appBaseUrl, it).lowercase(Locale.ROOT) }
                        .thenBy { it.id }
                )
            withContext(Dispatchers.Main) {
                if (subscriptions.isEmpty()) {
                    listView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                } else {
                    listView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    listView.adapter = ShortcutSubscriptionAdapter(
                        subscriptions = subscriptions,
                        appBaseUrl = appBaseUrl,
                        onClick = { subscription -> onSubscriptionSelected(subscription) }
                    )
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        setResult(RESULT_CANCELED)
        finish()
        return true
    }

    private fun onSubscriptionSelected(subscription: Subscription) {
        Log.d(TAG, "Creating shortcut for subscription ${subscription.id} (${subscriptionTopicShortUrl(subscription)})")
        val resultIntent = AppShortcutManager.createShortcutResultIntent(this, subscription)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private class ShortcutSubscriptionAdapter(
        private val subscriptions: List<Subscription>,
        private val appBaseUrl: String,
        private val onClick: (Subscription) -> Unit
    ) : RecyclerView.Adapter<ShortcutSubscriptionAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.fragment_shortcut_item, parent, false)
            return ViewHolder(view, appBaseUrl, onClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(subscriptions[position])
        }

        override fun getItemCount(): Int = subscriptions.size

        class ViewHolder(
            itemView: View,
            private val appBaseUrl: String,
            private val onClick: (Subscription) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            private val context: Context = itemView.context
            private val imageView: ImageView = itemView.findViewById(R.id.shortcut_item_image)
            private val titleView: TextView = itemView.findViewById(R.id.shortcut_item_title)
            private val subtitleView: TextView = itemView.findViewById(R.id.shortcut_item_subtitle)

            fun bind(subscription: Subscription) {
                val bitmap = subscription.icon?.readBitmapFromUriOrNull(context)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageResource(R.drawable.ic_sms_gray_24dp)
                }
                val title = displayName(appBaseUrl, subscription)
                val topicShort = subscriptionTopicShortUrl(subscription)
                titleView.text = title
                subtitleView.text = topicShort
                subtitleView.visibility = if (title != topicShort) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onClick(subscription) }
            }
        }
    }

    companion object {
        private const val TAG = "NtfyShortcutActivity"
    }
}
