package com.thanksmister.iot.mqtt.alarmpanel.network


import android.R.id.message
import android.content.Context
import android.text.TextUtils
import com.thanksmister.iot.mqtt.alarmpanel.R
import com.thanksmister.iot.mqtt.alarmpanel.utils.MqttUtils
import com.thanksmister.iot.mqtt.alarmpanel.utils.StringUtils
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException
import java.util.concurrent.atomic.AtomicBoolean

class MQTTService(private var context: Context, options: MQTTOptions,
                  private var listener: MqttManagerListener?) : MQTTServiceInterface {

    private var mqttClient: MqttAndroidClient? = null
    private var mqttOptions: MQTTOptions? = null
    private val mReady = AtomicBoolean(false)

    init {
        initialize(options)
    }

    override fun reconfigure(context: Context,
                             newOptions: MQTTOptions,
                             listener: MqttManagerListener) {
        try {
            close()
        } catch (e: MqttException) {
            // empty
        }
        this.listener = listener
        this.context = context
        initialize(newOptions)
    }

    interface MqttManagerListener {
        fun subscriptionMessage(id: String, topic: String, payload: String)
        fun handleMqttException(errorMessage: String)
        fun handleMqttDisconnected()
    }

    override fun isReady(): Boolean {
        return mReady.get()
    }

    @Throws(MqttException::class)
    override fun close() {
        Timber.d("close")
        if (mqttClient != null) {
            // TODO IllegalArgumentException: Invalid ClientHandle and no dialog showing sound stuck
            mqttClient?.setCallback(null)
            if (mqttClient!!.isConnected) {
                mqttClient!!.disconnect(0)
            }
            mqttClient = null
            listener = null
            mqttOptions = null
        }
        mReady.set(false)
    }

    override fun publish(payload: String) {
        try {
            if (isReady) {
                if (mqttClient != null && !mqttClient!!.isConnected) {
                    // if for some reason the mqtt client has disconnected, we should try to connect
                    // it again.
                    try {
                        initializeMqttClient()
                    } catch (e: MqttException) {
                        if (listener != null) {
                            listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                        }
                    } catch (e: IOException) {
                        if (listener != null) {
                            listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                        }
                    } catch (e: GeneralSecurityException) {
                        if (listener != null) {
                            listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                        }
                    }

                }
                Timber.d("Publishing: " + payload)
                Timber.d("Command Topic: " + mqttOptions?.getCommandTopic())
                val mqttMessage = MqttMessage()
                mqttMessage.payload = payload.toByteArray()
                mqttMessage.isRetained = true // we want to remember last state
                sendMessage(mqttOptions?.getCommandTopic(), mqttMessage)
            }
        } catch (e: MqttException) {
            if (listener != null) {
                listener!!.handleMqttException("Exception while subscribing: " + e.message)
            }
        }

    }

    /**
     * Initialize a Cloud IoT Endpoint given a set of configuration options.
     * @param options Cloud IoT configuration options.
     */
    private fun initialize(options: MQTTOptions) {
        Timber.d("initialize")
        try {
            mqttOptions = options
            Timber.i("Service Configuration:")
            Timber.i("Client ID: " + mqttOptions!!.getClientId())
            Timber.i("Username: " + mqttOptions!!.getUsername())
            Timber.i("Password: " + mqttOptions!!.getPassword())
            Timber.i("TslConnect: " + mqttOptions!!.getTlsConnection())
            Timber.i("MQTT Configuration:")
            Timber.i("Broker: " + mqttOptions?.brokerUrl)
            Timber.i("Subscibed to topics: " + StringUtils.convertArrayToString(mqttOptions!!.getStateTopics()))
            Timber.i("Publishing to topic: " + mqttOptions!!.getCommandTopic())
            if (mqttOptions!!.isValid) {
                initializeMqttClient()
            } else {
                if (listener != null) {
                    listener!!.handleMqttDisconnected()
                }
            }
        } catch (e: MqttException) {
            if (listener != null) {
                //listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                listener!!.handleMqttException(context.getString(R.string.error_mqtt_connection))
            }
        } catch (e: IOException) {
            if (listener != null) {
                //listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                listener!!.handleMqttException(context.getString(R.string.error_mqtt_connection))
            }
        } catch (e: GeneralSecurityException) {
            if (listener != null) {
                //listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                listener!!.handleMqttException(context.getString(R.string.error_mqtt_connection))
            }
        }
    }

    @Throws(MqttException::class, IOException::class, NoSuchAlgorithmException::class, InvalidKeySpecException::class)
    private fun initializeMqttClient() {
        Timber.d("initializeMqttClient")
        try {
            mqttClient = MqttAndroidClient(context, mqttOptions?.brokerUrl, mqttOptions!!.getClientId())
            val options = MqttConnectOptions()
            if (!TextUtils.isEmpty(mqttOptions!!.getUsername()) && !TextUtils.isEmpty(mqttOptions!!.getPassword())) {
                options.userName = mqttOptions!!.getUsername()
                options.password = mqttOptions!!.getPassword().toCharArray()
            }

            options.isAutomaticReconnect = true
            options.isCleanSession = false

            try {
                mqttClient!!.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken) {
                        val disconnectedBufferOptions = DisconnectedBufferOptions()
                        disconnectedBufferOptions.isBufferEnabled = true
                        disconnectedBufferOptions.bufferSize = 100
                        disconnectedBufferOptions.isPersistBuffer = false
                        disconnectedBufferOptions.isDeleteOldestMessages = false
                        if (mqttClient != null) {
                            mqttClient!!.setBufferOpts(disconnectedBufferOptions)
                        }
                        if (mqttOptions != null) {
                            subscribeToTopics(mqttOptions!!.getStateTopics())
                        }
                    }

                    override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                        if (listener != null && mqttOptions != null) {
                            Timber.e("Failed to connect to: " + mqttOptions?.brokerUrl + " exception: " + exception)
                            //listener!!.handleMqttException("Error connecting to the broker and port: " + mqttOptions!!.brokerUrl)
                            listener!!.handleMqttException(context.getString(R.string.error_mqtt_subscription))
                        }
                    }
                })
            } catch (e: MqttException) {
                Timber.e(e, "MqttException")
                if (listener != null) {
                    listener?.handleMqttException("" + e.message)
                }
            }

            mReady.set(true)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
            if (listener != null) {
                listener!!.handleMqttException("" + e.message)
            }
        }
    }

    @Throws(MqttException::class)
    private fun sendMessage(mqttTopic: String?, mqttMessage: MqttMessage) {
        Timber.d("sendMessage")
        if (isReady && mqttClient != null && mqttClient!!.isConnected) {
            try {
                mqttClient!!.publish(mqttTopic, mqttMessage)
                Timber.d("Command Topic: $mqttTopic Payload: $message")
            } catch (e: MqttException) {
                Timber.e("Error Sending Command: " + e.message)
                e.printStackTrace()
                if (listener != null) {
                    //listener!!.handleMqttException("Error Sending Command: " + e.message)
                    listener!!.handleMqttException(context.getString(R.string.error_mqtt_subscription))
                }
            }
        }
    }

    private fun subscribeToTopics(topicFilters: Array<String>?) {
        Timber.d("Subscribe to Topics: " + StringUtils.convertArrayToString(topicFilters))
        try {
            if (isReady && mqttClient != null) {
                mqttClient!!.subscribe(topicFilters, MqttUtils.getQos(topicFilters!!.size), MqttUtils.getMqttMessageListeners(topicFilters.size, listener))
            }
        } catch (e: MqttException) {
            if (listener != null) {
                listener!!.handleMqttException("Exception while subscribing: " + e.message)
            }
        }
    }

    companion object {
        // Indicate if this message should be a MQTT 'retained' message.
        private val SHOULD_RETAIN = false

        // Use mqttQos=1 (at least once delivery), mqttQos=0 (at most once delivery) also supported.
        private val MQTT_QOS = 0
    }
}