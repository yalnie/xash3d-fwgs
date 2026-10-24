/*
android_nosdl.c - android backend
Copyright (C) 2016-2019 mittorn

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
*/
#include "platform/platform.h"
#include "input.h"
#include "client.h"
#include "sound.h"
#include "errno.h"
#include <pthread.h>
#include <sys/prctl.h>

#include <android/log.h>
#include <jni.h>
#if XASH_SDL
#include <SDL.h>
#endif // XASH_SDL

struct jnimethods_s
{
	JNIEnv *env;
	jobject activity;
	jclass actcls;
	jmethodID loadAndroidID;
	jmethodID getAndroidID;
	jmethodID saveAndroidID;
	jmethodID getWindowInsets;
	jmethodID onConsoleOutput;
	pthread_t thread;
	qboolean dedicated;
} jni;

#define ANDROID_CMD_QUEUE_SIZE 64
static pthread_mutex_t cmd_mutex = PTHREAD_MUTEX_INITIALIZER;
static char *cmd_queue[ANDROID_CMD_QUEUE_SIZE];
static int cmd_head = 0;
static int cmd_tail = 0;
static char cmd_buf[MAX_PRINT_MSG];

void Android_Init( void )
{
	if( jni.dedicated )
		return;

	memset( &jni, 0, sizeof( jni ));

#if XASH_SDL
	jni.env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jni.activity = (jobject)SDL_AndroidGetActivity();
	jni.actcls = (*jni.env)->GetObjectClass( jni.env, jni.activity );
	jni.loadAndroidID = (*jni.env)->GetMethodID( jni.env, jni.actcls, "loadAndroidID", "()Ljava/lang/String;" );
	jni.getAndroidID = (*jni.env)->GetMethodID( jni.env, jni.actcls, "getAndroidID", "()Ljava/lang/String;" );
	jni.saveAndroidID = (*jni.env)->GetMethodID( jni.env, jni.actcls, "saveAndroidID", "(Ljava/lang/String;)V" );
	jni.getWindowInsets = (*jni.env)->GetMethodID( jni.env, jni.actcls, "getWindowInsets", "()[F" );
#endif // !XASH_SDL
}

void Android_Shutdown( void )
{
	pthread_mutex_lock( &cmd_mutex );
	while( cmd_head != cmd_tail )
	{
		free( cmd_queue[cmd_head] );
		cmd_queue[cmd_head] = NULL;
		cmd_head = ( cmd_head + 1 ) % ANDROID_CMD_QUEUE_SIZE;
	}
	pthread_mutex_unlock( &cmd_mutex );
}

/*
========================
Android_GetNativeObject
========================
*/

void *Android_GetNativeObject( const char *name )
{
	if( !strcasecmp( name, "JNIEnv" ) )
	{
		return (void *)jni.env;
	}
	else if( !strcasecmp( name, "ActivityClass" ) )
	{
		return (void *)jni.actcls;
	}

	return NULL;
}

/*
========================
Android_GetAndroidID
========================
*/
const char *Android_GetAndroidID( void )
{
	static char id[32];

	if( !COM_StringEmpty( id ))
		return id;

	if( !jni.env || !jni.activity || !jni.getAndroidID )
		return "";

	jstring resultJNIStr = (*jni.env)->CallObjectMethod( jni.env, jni.activity, jni.getAndroidID );
	const char *resultCStr = (*jni.env)->GetStringUTFChars( jni.env, resultJNIStr, NULL );
	Q_strncpy( id, resultCStr, sizeof( id ) );
	(*jni.env)->ReleaseStringUTFChars( jni.env, resultJNIStr, resultCStr );
	(*jni.env)->DeleteLocalRef( jni.env, resultJNIStr );

	return id;
}

/*
========================
Android_LoadID
========================
*/
const char *Android_LoadID( void )
{
	static char id[32];

	if( !jni.env || !jni.activity || !jni.loadAndroidID )
		return "";

	jstring resultJNIStr = (*jni.env)->CallObjectMethod( jni.env, jni.activity, jni.loadAndroidID );
	const char *resultCStr = (*jni.env)->GetStringUTFChars( jni.env, resultJNIStr, NULL );
	Q_strncpy( id, resultCStr, sizeof( id ) );
	(*jni.env)->ReleaseStringUTFChars( jni.env, resultJNIStr, resultCStr );
	(*jni.env)->DeleteLocalRef( jni.env, resultJNIStr );

	return id;
}

/*
========================
Android_SaveID
========================
*/
void Android_SaveID( const char *id )
{
	if( !jni.env || !jni.activity || !jni.saveAndroidID )
		return;

	jstring JStr = (*jni.env)->NewStringUTF( jni.env, id );
	(*jni.env)->CallVoidMethod( jni.env, jni.activity, jni.saveAndroidID, JStr );
	(*jni.env)->DeleteLocalRef( jni.env, JStr );
}

/*
========================
Android_ShellExecute
========================
*/
void Platform_ShellExecute( const char *path, const char *parms )
{
#if XASH_SDL
	if( !Host_IsDedicated( ))
		SDL_OpenURL( path );
#endif // XASH_SDL
}

/*
========================
Android_GetWindowInsets
========================
*/
void Android_GetWindowInsets( float insets[4] )
{
	if( !jni.env || !jni.activity || !jni.getWindowInsets )
	{
		insets[0] = insets[1] = insets[2] = insets[3] = 0.0f;
		return;
	}

	jfloatArray result = (*jni.env)->CallObjectMethod( jni.env, jni.activity, jni.getWindowInsets );
	(*jni.env)->GetFloatArrayRegion( jni.env, result, 0, 4, insets );
	(*jni.env)->DeleteLocalRef( jni.env, result );
}

/*
========================
Android_Input
========================
*/
char *Android_Input( void )
{
	char *cmd = NULL;

	pthread_mutex_lock( &cmd_mutex );
	if( cmd_head != cmd_tail )
	{
		cmd = cmd_queue[cmd_head];
		cmd_queue[cmd_head] = NULL;
		cmd_head = ( cmd_head + 1 ) % ANDROID_CMD_QUEUE_SIZE;
	}
	pthread_mutex_unlock( &cmd_mutex );

	if( !cmd )
		return NULL;

	Q_strncpy( cmd_buf, cmd, sizeof( cmd_buf ));
	free( cmd );

	return cmd_buf;
}

/*
========================
Android_Print
========================
*/
void Android_Print( const char *msg )
{
	size_t len;
	jbyteArray arr;

	if( !jni.dedicated || !jni.env || !jni.activity || !jni.onConsoleOutput || !msg )
		return;

	if( host.status == HOST_CRASHED || !pthread_equal( pthread_self(), jni.thread ))
		return;

	len = Q_strlen( msg );
	if( !len )
		return;

	arr = (*jni.env)->NewByteArray( jni.env, len );
	if( arr )
	{
		(*jni.env)->SetByteArrayRegion( jni.env, arr, 0, len, (const jbyte *)msg );
		(*jni.env)->CallVoidMethod( jni.env, jni.activity, jni.onConsoleOutput, arr );
		(*jni.env)->DeleteLocalRef( jni.env, arr );
	}
}

JNIEXPORT jint JNICALL Java_su_xash_engine_DedicatedService_nativeMain( JNIEnv *env, jobject thiz, jobjectArray jargv )
{
	int i, argc;
	char **argv;
	int ret;
	const char *gamedir;

	memset( &jni, 0, sizeof( jni ));
	jni.dedicated = true;
	jni.env = env;
	jni.thread = pthread_self();
	jni.activity = (*env)->NewGlobalRef( env, thiz );
	jni.actcls = (*env)->NewGlobalRef( env, (*env)->GetObjectClass( env, thiz ));
	jni.onConsoleOutput = (*env)->GetMethodID( env, jni.actcls, "onConsoleOutput", "([B)V" );

	argc = (*env)->GetArrayLength( env, jargv );
	argv = calloc( argc + 1, sizeof( char * ));
	for( i = 0; i < argc; i++ )
	{
		jstring str = (jstring)(*env)->GetObjectArrayElement( env, jargv, i );
		const char *utf = (*env)->GetStringUTFChars( env, str, NULL );
		argv[i] = strdup( utf ? utf : "" );
		if( utf )
			(*env)->ReleaseStringUTFChars( env, str, utf );
		(*env)->DeleteLocalRef( env, str );
	}
	argv[argc] = NULL;

	prctl( PR_SET_DUMPABLE, 1 );

	gamedir = getenv( "XASH3D_GAME" );
	if( COM_StringEmptyOrNULL( gamedir ))
		gamedir = "valve";

	ret = Host_Main( argc, argv, gamedir, 0, NULL );

	for( i = 0; i < argc; i++ )
		free( argv[i] );
	free( argv );

	(*env)->DeleteGlobalRef( env, jni.activity );
	(*env)->DeleteGlobalRef( env, jni.actcls );
	memset( &jni, 0, sizeof( jni ));

	return ret;
}

JNIEXPORT void JNICALL Java_su_xash_engine_DedicatedService_nativeSendCommand( JNIEnv *env, jclass cls, jstring jcmd )
{
	const char *utf;
	size_t len;
	char *copy;
	int next;

	if( !jcmd )
		return;

	utf = (*env)->GetStringUTFChars( env, jcmd, NULL );
	if( !utf )
		return;

	len = Q_strlen( utf );
	copy = malloc( len + 2 );
	if( copy )
	{
		memcpy( copy, utf, len );
		if( len == 0 || copy[len - 1] != '\n' )
		{
			copy[len] = '\n';
			copy[len + 1] = '\0';
		}
		else
		{
			copy[len] = '\0';
		}

		pthread_mutex_lock( &cmd_mutex );
		next = ( cmd_tail + 1 ) % ANDROID_CMD_QUEUE_SIZE;
		if( next != cmd_head )
		{
			cmd_queue[cmd_tail] = copy;
			cmd_tail = next;
			copy = NULL;
		}
		pthread_mutex_unlock( &cmd_mutex );

		if( copy )
			free( copy );
	}

	(*env)->ReleaseStringUTFChars( env, jcmd, utf );
}

JNIEXPORT void JNICALL Java_su_xash_engine_DedicatedService_nativeSetenv( JNIEnv *env, jclass cls, jstring jname, jstring jvalue )
{
	const char *name;
	const char *value;

	if( !jname || !jvalue )
		return;

	name = (*env)->GetStringUTFChars( env, jname, NULL );
	value = (*env)->GetStringUTFChars( env, jvalue, NULL );
	if( name && value )
		setenv( name, value, 1 );
	if( name )
		(*env)->ReleaseStringUTFChars( env, jname, name );
	if( value )
		(*env)->ReleaseStringUTFChars( env, jvalue, value );
}
