package com.codingwithmitch.openapi.repository.main

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.switchMap
import com.codingwithmitch.openapi.api.ApiSuccessResponse
import com.codingwithmitch.openapi.api.GenericApiResponse
import com.codingwithmitch.openapi.api.main.OpenApiMainService
import com.codingwithmitch.openapi.api.main.network_responses.BlogListSearchResponse
import com.codingwithmitch.openapi.models.AuthToken
import com.codingwithmitch.openapi.models.BlogPost
import com.codingwithmitch.openapi.persistence.BlogPostDao
import com.codingwithmitch.openapi.repository.NetworkBoundResource
import com.codingwithmitch.openapi.ui.DataState
import com.codingwithmitch.openapi.ui.main.account.state.AccountViewState
import com.codingwithmitch.openapi.ui.main.blog.state.BlogViewState
import com.codingwithmitch.openapi.util.DateUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import javax.inject.Inject

class BlogRepository
@Inject
constructor(
    val openApiMainService: OpenApiMainService,
    val blogPostDao: BlogPostDao
)
{
    private val TAG: String = "AppDebug"

    private var job: Job? = null

    fun searchBlogPosts(authToken: AuthToken, query: String, ordering: String, page: Int): LiveData<DataState<BlogViewState>> {

        return object: NetworkBoundResource<BlogListSearchResponse, List<BlogPost>, BlogViewState>(){

            // not used in this case
            override suspend fun createCacheRequestAndReturn() {
            }

            override suspend fun handleApiSuccessResponse(response: ApiSuccessResponse<BlogListSearchResponse>) {
                val blogPostList: ArrayList<BlogPost> = ArrayList()
                for(blogPostResponse in response.body.results){
                    blogPostList.add(
                        BlogPost(
                            pk = blogPostResponse.pk,
                            title = blogPostResponse.title,
                            slug = blogPostResponse.slug,
                            body = blogPostResponse.body,
                            image = blogPostResponse.image,
                            date_updated = DateUtils.convertServerStringDateToLong(blogPostResponse.date_updated),
                            username = blogPostResponse.username
                        )
                    )
                }
                updateLocalDb(blogPostList)

                withContext(Dispatchers.Main){

                    // finishing by viewing db cache
                    addSourceToResult(loadFromCache(), false)
                }
            }

            override fun shouldLoadFromCache(): Boolean {
                return true
            }

            override fun loadFromCache(): LiveData<BlogViewState> {
                return BlogQueryUtils.returnOrderedBlogQuery(
                    blogPostDao = blogPostDao,
                    query = query,
                    ordering = ordering,
                    page = page)
                    .switchMap {
                        object: LiveData<BlogViewState>(){
                            override fun onActive() {
                                super.onActive()
                                value = BlogViewState(blogList = it)
                            }
                        }
                    }
            }

            override suspend fun updateLocalDb(cacheObject: List<BlogPost>?) {
                // loop through list and update the local db
                if(cacheObject != null){
                    withContext(IO) {
                        for(blogPost in cacheObject){
                            try{
                                // Launch each insert as a separate job to be executed in parallel
                                launch {
                                    Log.d(TAG, "updateLocalDb: inserting blog: ${blogPost}")
                                    blogPostDao.insert(blogPost)
                                }
                            }catch (e: Exception){
                                Log.e(TAG, "updateLocalDb: error updating cache data on blog post with slug: ${blogPost.slug}. " +
                                        "${e.message}")
                                // Could send an error report here or something but I don't think you should throw an error to the UI
                                // Since there could be many blog posts being inserted/updated.
                            }
                        }
                    }
                }
                else{
                    Log.d(TAG, "updateLocalDb: blog post list is null")
                }
            }

            override fun cancelOperationIfNoInternetConnection(): Boolean {
                return false
            }

            override fun createCall(): LiveData<GenericApiResponse<BlogListSearchResponse>> {
                return openApiMainService.searchListBlogPosts(
                    "Token ${authToken.token!!}",
                    query = query,
                    ordering = ordering,
                    page = page
                )
            }

            override fun setCurrentJob(job: Job) {
                this@BlogRepository.job?.cancel() // cancel existing jobs
                this@BlogRepository.job = job
            }

            override fun isNetworkRequest(): Boolean {
                return true
            }

        }.asLiveData()
    }

    fun cancelRequests(){
        Log.d(TAG, "AccountRepository: cancelling requests... ")
        job?.cancel()
    }

}

























