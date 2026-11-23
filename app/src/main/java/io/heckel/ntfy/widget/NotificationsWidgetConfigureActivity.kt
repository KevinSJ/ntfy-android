package io.heckel.ntfy.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.isDarkThemeOn
import io.heckel.ntfy.util.readBitmapFromUriOrNull
import io.heckel.ntfy.util.subscriptionTopicShortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuration (and reconfiguration) activity for [NotificationsWidgetProvider].
 * Lets the user choose between showing notifications from all topics or a single topic.
 */
class NotificationsWidgetConfigureActivity : AppCompatActivity() {
    private val repository by lazy { (application as Application).repository }
    private lateinit var appBaseUrl: String
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Cancel widget placement if the user backs out without selecting an option
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_configure)
        Log.init(this)
        Log.d(TAG, "Configuring notifications widget $appWidgetId")

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
        title = getString(R.string.widget_config_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            Colors.shouldUseLightStatusBar(dynamicColors, darkMode)

        val allTopicsCard = findViewById<View>(R.id.widget_config_all_topics_card)
        allTopicsCard.setOnClickListener {
            onSelectionConfirmed(Repository.WIDGET_SUBSCRIPTION_ALL)
        }

        val topicsHeader = findViewById<View>(R.id.widget_config_topics_header)
        val topicsList = findViewById<RecyclerView>(R.id.widget_config_topics_list)
        ViewCompat.setOnApplyWindowInsetsListener(topicsList) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            insets
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val subscriptions = WidgetHelper.sortSubscriptionsForWidget(
                subscriptions = repository.getSubscriptions(),
                appBaseUrl = appBaseUrl
            )
            withContext(Dispatchers.Main) {
                if (subscriptions.isEmpty()) {
                    topicsHeader.visibility = View.GONE
                    topicsList.visibility = View.GONE
                } else {
                    topicsHeader.visibility = View.VISIBLE
                    topicsList.visibility = View.VISIBLE
                    topicsList.adapter = TopicPickerAdapter(
                        subscriptions = subscriptions,
                        appBaseUrl = appBaseUrl,
                        onClick = { subscription -> onSelectionConfirmed(subscription.id) }
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

    private fun onSelectionConfirmed(subscriptionId: Long) {
        repository.setWidgetSubscriptionId(appWidgetId, subscriptionId)
        val appContext = applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        lifecycleScope.launch(Dispatchers.IO) {
            NotificationsWidgetProvider.updateWidgets(appContext, manager, intArrayOf(appWidgetId))
            withContext(Dispatchers.Main) {
                val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                setResult(RESULT_OK, resultValue)
                finish()
            }
        }
    }

    private class TopicPickerAdapter(
        private val subscriptions: List<Subscription>,
        private val appBaseUrl: String,
        private val onClick: (Subscription) -> Unit
    ) : RecyclerView.Adapter<TopicPickerAdapter.ViewHolder>() {

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
        private const val TAG = "NtfyWidgetConfig"
    }
}
