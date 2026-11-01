/*
Optional Hamlib rig control (CAT frequency and PTT) for Rattlegram Plus Plus.

Built as its own library, librattlegram-rig.so, which links against Hamlib's
libhamlib.so (LGPL-2.1-or-later, see hamlib/README.md). The app loads it only
after the user turns on Hamlib rig control, so the default app never loads
Hamlib at all.

Android apps cannot open /dev/ttyUSB*, so the Java side owns the USB serial
adapter (RigUsbSerial.java) and lends Hamlib a byte pipe through the
"android-usb:<deviceId>:<portIndex>" serial path added by the patched Hamlib
build (rpt_usb_serial_register). Every call into Hamlib comes from the app's
single rig thread; Hamlib's own bridge threads call back into Java here.
*/

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <hamlib/rig.h>
#include <hamlib/rpt_usb_serial.h>

#define SERIAL_CLASS "com/bi3bju/rattlegram_plus/RigUsbSerial"

static JavaVM *javaVM;
static jclass serialClass;
static jmethodID midIsReady, midOpen, midRead, midWrite, midSetRts, midSetDtr, midFlush, midClose;
static pthread_key_t detachKey;

#define LOG_TAG "RattlegramRig"

/* Hamlib's warnings and errors go to logcat (tag RattlegramRig) instead of a stderr nobody sees. */
static int logHamlib(enum rig_debug_level_e level, rig_ptr_t arg, const char *format, va_list args) {
	(void) arg;
	__android_log_vprint(level <= RIG_DEBUG_ERR ? ANDROID_LOG_ERROR : level <= RIG_DEBUG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG, LOG_TAG, format, args);
	return 0;
}

static void detachThread(void *unused) {
	(void) unused;
	(*javaVM)->DetachCurrentThread(javaVM);
}

/* JNIEnv for the calling thread, attaching Hamlib's bridge threads on first use (detached when they exit). */
static JNIEnv *threadEnv(void) {
	JNIEnv *env = NULL;
	if ((*javaVM)->GetEnv(javaVM, (void **) &env, JNI_VERSION_1_6) == JNI_OK)
		return env;
	if ((*javaVM)->AttachCurrentThread(javaVM, &env, NULL) != JNI_OK)
		return NULL;
	pthread_setspecific(detachKey, env);
	return env;
}

static int callInt(jmethodID mid, ...) {
	JNIEnv *env = threadEnv();
	if (!env)
		return -1;
	va_list args;
	va_start(args, mid);
	jint result = (*env)->CallStaticIntMethodV(env, serialClass, mid, args);
	va_end(args);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		return -1;
	}
	return result;
}

static int opIsReady(void) {
	return callInt(midIsReady);
}

static int opOpen(int deviceId, int portIndex, int baudRate, int dataBits, int stopBits, int parity) {
	return callInt(midOpen, deviceId, portIndex, baudRate, dataBits, stopBits, parity);
}

static int opRead(unsigned char *buffer, int length, int timeoutMs) {
	JNIEnv *env = threadEnv();
	if (!env || length <= 0)
		return length == 0 ? 0 : -1;
	jbyteArray array = (*env)->NewByteArray(env, length);
	if (!array)
		return -1;
	jint count = (*env)->CallStaticIntMethod(env, serialClass, midRead, array, length, timeoutMs);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		count = -1;
	}
	if (count > 0)
		(*env)->GetByteArrayRegion(env, array, 0, count, (jbyte *) buffer);
	(*env)->DeleteLocalRef(env, array);
	return count;
}

static int opWrite(const unsigned char *buffer, int length, int timeoutMs) {
	JNIEnv *env = threadEnv();
	if (!env || length <= 0)
		return length == 0 ? 0 : -1;
	jbyteArray array = (*env)->NewByteArray(env, length);
	if (!array)
		return -1;
	(*env)->SetByteArrayRegion(env, array, 0, length, (const jbyte *) buffer);
	jint count = (*env)->CallStaticIntMethod(env, serialClass, midWrite, array, length, timeoutMs);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		count = -1;
	}
	(*env)->DeleteLocalRef(env, array);
	return count;
}

static int opSetRts(int state) {
	return callInt(midSetRts, state);
}

static int opSetDtr(int state) {
	return callInt(midSetDtr, state);
}

static int opFlush(void) {
	return callInt(midFlush);
}

static int opClose(void) {
	return callInt(midClose);
}

static const rpt_usb_serial_ops_t serialOps = {
	opIsReady, opOpen, opRead, opWrite, opSetRts, opSetDtr, opFlush, opClose
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
	(void) reserved;
	javaVM = vm;
	JNIEnv *env;
	if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
		return JNI_ERR;
	jclass local = (*env)->FindClass(env, SERIAL_CLASS);
	if (!local)
		return JNI_ERR;
	serialClass = (*env)->NewGlobalRef(env, local);
	midIsReady = (*env)->GetStaticMethodID(env, serialClass, "isReady", "()I");
	midOpen = (*env)->GetStaticMethodID(env, serialClass, "open", "(IIIIII)I");
	midRead = (*env)->GetStaticMethodID(env, serialClass, "read", "([BII)I");
	midWrite = (*env)->GetStaticMethodID(env, serialClass, "write", "([BII)I");
	midSetRts = (*env)->GetStaticMethodID(env, serialClass, "setRts", "(I)I");
	midSetDtr = (*env)->GetStaticMethodID(env, serialClass, "setDtr", "(I)I");
	midFlush = (*env)->GetStaticMethodID(env, serialClass, "flush", "()I");
	midClose = (*env)->GetStaticMethodID(env, serialClass, "close", "()I");
	if (!midIsReady || !midOpen || !midRead || !midWrite || !midSetRts || !midSetDtr || !midFlush || !midClose)
		return JNI_ERR;
	pthread_key_create(&detachKey, detachThread);
	rig_set_debug_callback(logHamlib, NULL);
	rig_set_debug(RIG_DEBUG_WARN);
	if (rpt_usb_serial_register(&serialOps) != RIG_OK)
		return JNI_ERR;
	return JNI_VERSION_1_6;
}

static int collectModel(const struct rig_caps *caps, rig_ptr_t data) {
	JNIEnv *env = threadEnv();
	jobject list = (jobject) data;
	char line[160];
	snprintf(line, sizeof(line), "%u\t%s\t%s", (unsigned) caps->rig_model, caps->mfg_name, caps->model_name);
	jstring item = (*env)->NewStringUTF(env, line);
	jclass listClass = (*env)->GetObjectClass(env, list);
	jmethodID add = (*env)->GetMethodID(env, listClass, "add", "(Ljava/lang/Object;)Z");
	(*env)->CallBooleanMethod(env, list, add, item);
	(*env)->DeleteLocalRef(env, item);
	(*env)->DeleteLocalRef(env, listClass);
	return 1; /* keep going */
}

/* Adds "model\tmanufacturer\tname" for every serial (CAT) rig Hamlib knows to the given List<String>. */
JNIEXPORT void JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeListModels(JNIEnv *env, jclass cls, jobject list) {
	(void) env;
	(void) cls;
	rig_load_all_backends();
	rig_list_foreach(collectModel, (rig_ptr_t) list);
}

/*
 * Serial settings and capabilities of one model: {rateMin, rateMax, dataBits, stopBits, parity, portType,
 * canGetFreq, canSetFreq, canSetPtt, canGetPtt}, or null if Hamlib does not know the model.
 */
JNIEXPORT jintArray JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeModelInfo(JNIEnv *env, jclass cls, jint model) {
	(void) cls;
	rig_load_all_backends();
	const struct rig_caps *caps = rig_get_caps((rig_model_t) model);
	if (!caps)
		return NULL;
	jint info[10] = {
		caps->serial_rate_min, caps->serial_rate_max, caps->serial_data_bits, caps->serial_stop_bits,
		caps->serial_parity, caps->port_type,
		caps->get_freq != NULL, caps->set_freq != NULL, caps->set_ptt != NULL, caps->get_ptt != NULL
	};
	jintArray result = (*env)->NewIntArray(env, 10);
	if (result)
		(*env)->SetIntArrayRegion(env, result, 0, 10, info);
	return result;
}

static int setConf(RIG *rig, const char *name, const char *value) {
	hamlib_token_t token = rig_token_lookup(rig, name);
	if (token == RIG_CONF_END)
		return -RIG_EINVAL;
	return rig_set_conf(rig, token, value);
}

/*
 * Opens the rig on the given serial path. Returns RIG_OK and stores the handle in handleOut[0], or returns a
 * negative Hamlib error code. (The handle is not returned directly: Android tags heap pointers, so a valid
 * handle can look negative as a jlong and be mistaken for an error.)
 * PTT is set to go through CAT ("RIG"), the only PTT method the bridge carries for every rig.
 */
JNIEXPORT jint JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeOpen(JNIEnv *env, jclass cls, jint model, jstring path,
		jint baudRate, jint dataBits, jint stopBits, jstring parity, jlongArray handleOut) {
	(void) cls;
	RIG *rig = rig_init((rig_model_t) model);
	if (!rig)
		return -RIG_ENIMPL;
	const char *pathChars = (*env)->GetStringUTFChars(env, path, NULL);
	const char *parityChars = (*env)->GetStringUTFChars(env, parity, NULL);
	char number[16];
	const char *step = "rig_pathname";
	int rc = setConf(rig, step, pathChars);
	if (rc == RIG_OK) {
		step = "serial_speed";
		snprintf(number, sizeof(number), "%d", baudRate);
		rc = setConf(rig, "serial_speed", number);
	}
	if (rc == RIG_OK) {
		step = "data_bits";
		snprintf(number, sizeof(number), "%d", dataBits);
		rc = setConf(rig, "data_bits", number);
	}
	if (rc == RIG_OK) {
		step = "stop_bits";
		snprintf(number, sizeof(number), "%d", stopBits);
		rc = setConf(rig, "stop_bits", number);
	}
	if (rc == RIG_OK) {
		step = "serial_parity";
		rc = setConf(rig, step, parityChars);
	}
	if (rc == RIG_OK) {
		step = "ptt_type";
		rc = setConf(rig, step, "RIG");
	}
	if (rc == RIG_OK) {
		/* No background polling: the app only talks to the radio when the user or a transmission needs it */
		step = "poll_interval";
		rc = setConf(rig, step, "0");
	}
	(*env)->ReleaseStringUTFChars(env, path, pathChars);
	(*env)->ReleaseStringUTFChars(env, parity, parityChars);
	if (rc == RIG_OK) {
		step = "rig_open";
		rc = rig_open(rig);
	}
	if (rc != RIG_OK) {
		__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "open failed at %s: %d %s", step, rc, rigerror2(rc));
		rig_cleanup(rig);
		return rc < 0 ? rc : -rc;
	}
	jlong handle = (jlong) (intptr_t) rig;
	(*env)->SetLongArrayRegion(env, handleOut, 0, 1, &handle);
	return RIG_OK;
}

JNIEXPORT void JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
	(void) env;
	(void) cls;
	RIG *rig = (RIG *) (intptr_t) handle;
	if (!rig)
		return;
	rig_close(rig);
	rig_cleanup(rig);
}

/* Current frequency in Hz, or a negative Hamlib error code. */
JNIEXPORT jdouble JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeGetFreq(JNIEnv *env, jclass cls, jlong handle) {
	(void) env;
	(void) cls;
	freq_t freq = 0;
	int rc = rig_get_freq((RIG *) (intptr_t) handle, RIG_VFO_CURR, &freq);
	return rc == RIG_OK ? freq : (rc < 0 ? rc : -rc);
}

JNIEXPORT jint JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeSetFreq(JNIEnv *env, jclass cls, jlong handle, jdouble hz) {
	(void) env;
	(void) cls;
	return rig_set_freq((RIG *) (intptr_t) handle, RIG_VFO_CURR, hz);
}

JNIEXPORT jint JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeSetPtt(JNIEnv *env, jclass cls, jlong handle, jboolean on) {
	(void) env;
	(void) cls;
	return rig_set_ptt((RIG *) (intptr_t) handle, RIG_VFO_CURR, on ? RIG_PTT_ON : RIG_PTT_OFF);
}

/* 1 if the radio reports it is transmitting, 0 if not, or a negative Hamlib error code. */
JNIEXPORT jint JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeGetPtt(JNIEnv *env, jclass cls, jlong handle) {
	(void) env;
	(void) cls;
	ptt_t ptt = RIG_PTT_OFF;
	int rc = rig_get_ptt((RIG *) (intptr_t) handle, RIG_VFO_CURR, &ptt);
	if (rc != RIG_OK)
		return rc < 0 ? rc : -rc;
	return ptt != RIG_PTT_OFF;
}

JNIEXPORT jstring JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeErrorText(JNIEnv *env, jclass cls, jint code) {
	(void) cls;
	/* rigerror() returns Hamlib's whole saved debug log; rigerror2() just the message */
	const char *text = rigerror2(code < 0 ? code : -code);
	return (*env)->NewStringUTF(env, text ? text : "Unknown error");
}

JNIEXPORT jstring JNICALL
Java_com_bi3bju_rattlegram_1plus_HamlibRig_nativeVersion(JNIEnv *env, jclass cls) {
	(void) cls;
	return (*env)->NewStringUTF(env, hamlib_version2);
}
