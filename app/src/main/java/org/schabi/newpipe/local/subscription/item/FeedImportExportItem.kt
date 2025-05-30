package org.schabi.newpipe.local.subscription.item

import android.graphics.Color
import android.graphics.PorterDuff
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.SearchView
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FeedImportExportGroupBinding
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.ktx.animateRotation
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.views.CollapsibleView

class FeedImportExportItem(
    val onImportPreviousSelected: () -> Unit,
    val onImportFromServiceSelected: (Int) -> Unit,
    val onExportSelected: () -> Unit,
    var isExpanded: Boolean = false,
    private val searchListener: (String) -> Unit
) : BindableItem<FeedImportExportGroupBinding>() {

    private var currentQuery = ""

    companion object {
        const val REFRESH_EXPANDED_STATUS = 123
        const val PAYLOAD_UPDATE_SEARCH = "update_search"
    }

    override fun bind(viewBinding: FeedImportExportGroupBinding, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(REFRESH_EXPANDED_STATUS)) {
            viewBinding.importExportOptions.apply { if (isExpanded) expand() else collapse() }
            return
        }

        // Get reference to the search view we'll add to the layout
        val searchView = viewBinding.subscriptionSearch

        // Clear any previous listeners to avoid duplicates
        searchView.setOnQueryTextListener(null)

        // Set the search view text to match the current filter state
        if (searchView.query.toString() != currentQuery) {
            searchView.setQuery(currentQuery, false)
        }

        // Set new listener
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true

            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText ?: ""
                currentQuery = query
                searchListener(query)
                return true
            }
        })


        super.bind(viewBinding, position, payloads)
    }

    // Method to update the search query from outside
    fun updateSearchQuery(query: String) {
        currentQuery = query
        // Force a refresh of the view
        notifyChanged(PAYLOAD_UPDATE_SEARCH)
    }


    override fun getLayout(): Int = R.layout.feed_import_export_group

    override fun bind(viewBinding: FeedImportExportGroupBinding, position: Int) {
        if (viewBinding.importFromOptions.childCount == 0) setupImportFromItems(viewBinding.importFromOptions)
        if (viewBinding.exportToOptions.childCount == 0) setupExportToItems(viewBinding.exportToOptions)

        expandIconListener?.let { viewBinding.importExportOptions.removeListener(it) }
        expandIconListener = CollapsibleView.StateListener { newState ->
            viewBinding.importExportExpandIcon.animateRotation(
                250, if (newState == CollapsibleView.COLLAPSED) 0 else 180
            )
        }

        viewBinding.importExportOptions.currentState = if (isExpanded) CollapsibleView.EXPANDED else CollapsibleView.COLLAPSED
        viewBinding.importExportExpandIcon.rotation = if (isExpanded) 180F else 0F
        viewBinding.importExportOptions.ready()

        viewBinding.importExportOptions.addListener(expandIconListener)
        viewBinding.importExport.setOnClickListener {
            viewBinding.importExportOptions.switchState()
            isExpanded = viewBinding.importExportOptions.currentState == CollapsibleView.EXPANDED
        }
    }

    override fun unbind(viewHolder: GroupieViewHolder<FeedImportExportGroupBinding>) {
        super.unbind(viewHolder)
        expandIconListener?.let { viewHolder.binding.importExportOptions.removeListener(it) }
        expandIconListener = null
    }

    override fun initializeViewBinding(view: View) = FeedImportExportGroupBinding.bind(view)

    private var expandIconListener: CollapsibleView.StateListener? = null

    private fun addItemView(title: String, @DrawableRes icon: Int, container: ViewGroup): View {
        val itemRoot = View.inflate(container.context, R.layout.subscription_import_export_item, null)
        val titleView = itemRoot.findViewById<TextView>(android.R.id.text1)
        val iconView = itemRoot.findViewById<ImageView>(android.R.id.icon1)

        titleView.text = title
        iconView.setImageResource(icon)

        container.addView(itemRoot)
        return itemRoot
    }

    private fun setupImportFromItems(listHolder: ViewGroup) {
        val previousBackupItem = addItemView(
            listHolder.context.getString(R.string.previous_export),
            R.drawable.ic_backup, listHolder
        )
        previousBackupItem.setOnClickListener { onImportPreviousSelected() }

        val iconColor = if (ThemeHelper.isLightThemeSelected(listHolder.context)) Color.BLACK else Color.WHITE
        val services = listHolder.context.resources.getStringArray(R.array.service_list)
        for (serviceName in services) {
            try {
                val service = NewPipe.getService(serviceName)

                val subscriptionExtractor = service.subscriptionExtractor ?: continue

                val supportedSources = subscriptionExtractor.supportedSources
                if (supportedSources.isEmpty()) continue

                val itemView = addItemView(serviceName, ServiceHelper.getIcon(service.serviceId), listHolder)
                val iconView = itemView.findViewById<ImageView>(android.R.id.icon1)
                iconView.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)

                itemView.setOnClickListener { onImportFromServiceSelected(service.serviceId) }
            } catch (e: ExtractionException) {
                throw RuntimeException("Services array contains an entry that it's not a valid service name ($serviceName)", e)
            }
        }
    }

    private fun setupExportToItems(listHolder: ViewGroup) {
        val previousBackupItem = addItemView(
            listHolder.context.getString(R.string.file),
            R.drawable.ic_save, listHolder
        )
        previousBackupItem.setOnClickListener { onExportSelected() }
    }
}
