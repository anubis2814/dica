package cool.mixi.dica.util

import android.content.Context
import android.os.Handler
import cool.mixi.dica.App
import cool.mixi.dica.R
import cool.mixi.dica.activity.StatusActivity
import cool.mixi.dica.activity.UserActivity
import cool.mixi.dica.adapter.StatusesAdapter
import cool.mixi.dica.bean.HashTag
import cool.mixi.dica.bean.Status
import cool.mixi.dica.database.AppDatabase
import cool.mixi.dica.fragment.ICompose
import org.jetbrains.anko.doAsync
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import javax.net.ssl.HttpsURLConnection



interface IStatusDataSource {
    fun sourceOld(): Call<List<Status>>?
    fun sourceNew(): Call<List<Status>>?
    fun loaded(data: List<Status>)
    fun requireRefresh()
}

class StatusTimeline(val context: Context, val table: androidx.recyclerview.widget.RecyclerView,
                     private val swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout,
                     private val dataSource: IStatusDataSource
) : androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener, ICompose {

    var selfRef: SoftReference<StatusTimeline>? = null

    var statuses = ArrayList<Status>()

    // is load more toast show
    private var noMoreDataToastShow = false

    // if everything is loaded
    var allLoaded: Boolean = false

    // for pull newest status
    var sinceId = 0

    // for load sourceOld status
    var maxId = 0

    // Handler
    private var mHandler: Handler? = null
    private var moreRunnable: MoreRunnable? = null

    fun init(): StatusTimeline {
        mHandler = Handler()
        selfRef = SoftReference(this)
        table.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        table.adapter = StatusesAdapter(statuses, context, WeakReference(this))
        table.setOnScrollListener(OnStatusTableScrollListener(selfRef))
        swipeRefreshLayout.setOnRefreshListener(this)
        swipeRefreshLayout.isRefreshing = true
        return this
    }

    override fun done() {
        dataSource?.requireRefresh()
    }

    fun clear(){
        statuses.clear()
    }

    fun addAll(data: List<Status>) {
        statuses.addAll(data)
    }

    fun add(status: Status) {
        statuses.add(status)
    }

    fun count(): Int {
        return statuses.size
    }

    fun resetQuery(){
        maxId = 0
        sinceId = 0
        allLoaded = false
    }

    override fun onRefresh() {
        loadNewest(null)
    }

    fun loadNewest(callback: IStatusDataSource?){
        dataSource.sourceNew()?.enqueue(StatuesCallback(this, true, callback))
    }

    class MoreRunnable(private val ref: SoftReference<StatusTimeline>, val callback: WeakReference<IStatusDataSource>?): Runnable {
        override fun run() {
            ref.get()?.let {
                if(callback == null) {
                    it.dataSource.sourceOld()?.enqueue(StatuesCallback(it, false, null))
                } else {
                    it.dataSource.sourceOld()?.enqueue(StatuesCallback(it, false, callback.get()))
                }
            }
        }
    }

    fun loadMore(callback: IStatusDataSource?){
        if(allLoaded){
            if(!noMoreDataToastShow && context !is StatusActivity){
                App.instance.toast(context.getString(R.string.all_data_load))
                noMoreDataToastShow = true
            }
            swipeRefreshLayout.isRefreshing = false
            return
        }

        if(moreRunnable == null){
            val cb: WeakReference<IStatusDataSource>? = if(callback != null){
                WeakReference(callback)
            } else {
                null
            }
            moreRunnable = MoreRunnable(selfRef!!, cb)
        }

        swipeRefreshLayout.isRefreshing = true
        mHandler?.removeCallbacks(moreRunnable)
        mHandler?.postDelayed(moreRunnable, 3000)
    }

    class OnStatusTableScrollListener(private val ref: SoftReference<StatusTimeline>?): androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
        private var lastVisibleItem: Int? = 0
        override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            ref?.get()?.let {
                recyclerView?.removeOnScrollListener(this)
            }

            if(newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE &&
                lastVisibleItem!! + 1 == recyclerView?.adapter?.itemCount) {
                ref?.get()?.let {
                    it.loadMore(null)
                }
            }
        }

        override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            val layoutManager = recyclerView?.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
            lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        }
    }

    class MyBindStatusGeoCallback(private val statusTimeline: SoftReference<StatusTimeline>?): IBindStatusGeo {
        override fun done(status: Status) {
            val adapter = statusTimeline?.get()?.table?.adapter as StatusesAdapter
            try {
                var pos = adapter.data.indexOf(status)
                if(statusTimeline.get()?.context is UserActivity){
                    pos+=1
                }
                adapter.notifyItemChanged(pos)
            }catch (e: Exception){}
        }
    }

    class StatuesCallback(timeline: StatusTimeline, insertMode: Boolean, private val callback: IStatusDataSource?):
        Callback<List<Status>> {
        private val ref = SoftReference<StatusTimeline>(timeline)
        private val insertMode = insertMode

        override fun onFailure(call: Call<List<Status>>, t: Throwable) {
            eLog("fail ${t.message}")
            ref.get()?.let {
                App.instance.toast(it.context.getString(R.string.common_error).format(t.message))
                it.swipeRefreshLayout.isRefreshing = false
            }
        }

        private fun showEmptyDataFetch(){
            ref.get()?.let {
                it.table?.adapter?.let { that ->
                    that as StatusesAdapter
                    that.initLoaded = true
                    that.notifyDataSetChanged()
                }
                it.swipeRefreshLayout.isRefreshing = false
            }
        }

        override fun onResponse(call: Call<List<Status>>, response: Response<List<Status>>) {
            if(ref.get() == null){
                return
            }

            val responseCode = response.code()
            if(responseCode != HttpsURLConnection.HTTP_OK){
                App.instance.toast(App.instance.getString(R.string.common_error).format("$responseCode - ${response.message()}"))
                showEmptyDataFetch()
                return
            }

            val act = ref.get()!!
            act.swipeRefreshLayout.isRefreshing = false
            if(response.body() == null) {
                callback?.loaded(ArrayList())
                return
            }

            val res = response.body()
            var tags = ArrayList<String>()

            // handle sinceId & maxId
            res?.forEachIndexed { idx, it ->
                if(it.id > act.sinceId) act.sinceId = it.id
                if(act.maxId == 0 || it.id < act.maxId) act.maxId = it.id

                // Bind Address if possible
                LocationUtil.instance.bindGeoAddress(it, MyBindStatusGeoCallback(act?.selfRef))

                FriendicaUtil.filterDuplicateLike(it)

                FriendicaUtil.statusPreProcess(it)

                it.text.toHashTagArray(tags)

                // NotSafeForWork
                it.enableNSFW = it.text.contains("#nsfw", true)

                // any more sourceOld status ?
                if(!insertMode && res?.count()!! <= 1 && act.statuses.contains(it)){
                    act.allLoaded = true
                    act.table.adapter?.notifyItemChanged(idx)
                    return
                }
            }

            // Save tag for later use
            doAsync {
                val tagDao = AppDatabase.getInstance().hashTagDao()
                tags.forEach { tagDao.add(HashTag(it)) }
            }

            // Cache user
            res?.let { App.instance.addUserToDB(it) }

            // handle data by itself
            if(callback != null){
                callback.loaded(res!!)
                return
            }

            // no any data
            if(act.statuses.size == 0 && res!!.isEmpty()){
                showEmptyDataFetch()
                return
            }

            if(insertMode) {
                res?.forEachIndexed continuing@ { _, it ->
                    if(act.statuses.contains(it)) { return@continuing }
                    act.statuses.add(0, it)
                }
                act.table.adapter?.notifyDataSetChanged()
            } else {
                res?.forEachIndexed continuing@ { _, it ->
                    if(act.statuses.contains(it)) { return@continuing }
                    act.statuses.add(it)
                    act.table.adapter?.notifyItemInserted(act?.table?.adapter?.itemCount ?: 0)
                }
            }
        }
    }
}