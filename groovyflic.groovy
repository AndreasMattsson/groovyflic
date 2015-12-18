#!/usr/bin/env groovy

@Grab(group='io.reactivex', module='rxgroovy', version='1.0.0')
@Grab(group='com.squareup.retrofit', module = 'retrofit', version = '1.9.0')
@Grab(group='com.appunite', module='websockets-rxjava', version='3.0.1')
@Grab(group='com.fasterxml.jackson.core', module='jackson-databind', version='2.6.4')
import rx.Observable
import rx.schedulers.*
import retrofit.RestAdapter
import retrofit.http.*
import com.squareup.okhttp.*
import com.appunite.websocket.rx.*
import com.appunite.websocket.rx.messages.*
import java.util.concurrent.*
import com.fasterxml.jackson.databind.ObjectMapper

final pushBulletEndpoint = 'https://api.pushbullet.com'
final pushBulletToken = System.getenv('PUSHBULLET_ACCESS_TOKEN')

String timestamp = System.currentTimeMillis() / 1000L

interface PushBulletRestService {
    @GET('/v2/users/me')
    Observable<Map> me()
	
	@GET('/v2/pushes')
	Observable<Map> pushes(@Query('modified_after') String timestamp)
}

final executor = Executors.newCachedThreadPool()

final pushBullet = new RestAdapter.Builder()
        .setEndpoint(pushBulletEndpoint)
		.setRequestInterceptor { request -> request.addHeader('Access-Token', pushBulletToken) }
		.setExecutors(executor, executor)
        .build()
        .create(PushBulletRestService)

pushBullet.me()
	.subscribe { println "/users/me: $it" }

final client = new OkHttpClient()
client.with { 
	setConnectTimeout(15, TimeUnit.SECONDS)
	setReadTimeout(15, TimeUnit.MINUTES)
}
final objectMapper = new ObjectMapper()
final subscription = new RxWebSockets(
		client,
		new Request.Builder()
			.get()
			.url("wss://stream.pushbullet.com/websocket/$pushBulletToken")
			.build()
	)
        .webSocketObservable()
		.subscribeOn(Schedulers.from(executor))
		.observeOn(Schedulers.from(executor))
		.filter { it instanceof RxEventStringMessage }
		.map { (it as RxEventStringMessage)?.message() }
		.map { objectMapper.readValue(it, Map) }
		.filter { it?.type == 'tickle' && it?.subtype == 'push' }
		.flatMap { pushBullet.pushes(timestamp).flatMap { Observable.from(it?.pushes ?: [] ) } }
		.filter { it?.active }
		.doOnNext { timestamp = it?.modified }
		.map { it?.title }
        .subscribe(
			{ rxEvent -> 
				println "Event: $rxEvent"
			},
			{ error ->
				println "Error: $error"
			},
			{
				
			}
		)
	
System.console()?.with {
	println 'Press ENTER to exit'
	readLine()
	subscription.unsubscribe()
	executor.shutdown()
	System.exit(0)
}