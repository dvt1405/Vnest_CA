package com.vnest.ca.activities;

import ai.api.model.AIContext;
import ai.api.model.AIOutputContext;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.github.zagum.speechrecognitionview.RecognitionProgressView;
import com.github.zagum.speechrecognitionview.adapters.RecognitionListenerAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kwabenaberko.openweathermaplib.constants.Lang;
import com.kwabenaberko.openweathermaplib.constants.Units;
import com.kwabenaberko.openweathermaplib.implementation.OpenWeatherMapHelper;
import com.kwabenaberko.openweathermaplib.implementation.callbacks.CurrentWeatherCallback;
import com.kwabenaberko.openweathermaplib.models.currentweather.CurrentWeather;
import com.vnest.ca.R;
import com.vnest.ca.adapters.MessageListAdapter;
import com.vnest.ca.entity.Audio;
import com.vnest.ca.entity.Message;
import com.vnest.ca.entity.MyAIContext;
import com.vnest.ca.entity.Youtube;
import com.vnest.ca.triggerword.Trigger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ai.api.AIServiceException;
import ai.api.RequestExtras;
import ai.api.android.AIConfiguration;
import ai.api.android.AIService;
import ai.api.model.AIRequest;
import ai.api.model.AIResponse;
import edu.cmu.pocketsphinx.Assets;
import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.RecognitionListener;
import edu.cmu.pocketsphinx.SpeechRecognizerSetup;

public class MainActivity extends AppCompatActivity implements LocationListener, RecognitionListener {

    private static final String LOG_TAG = "VNest";

    private String[] permissions = {Manifest.permission.INTERNET,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.VIBRATE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.SET_ALARM};

    private static final String KWS_SEARCH = "wakeup";
    private static final String KEYPHRASE = "wakeup";

    private RecyclerView mMessageRecycler;
    private List<Message> messageList;
    private MessageListAdapter mMessageAdapter;
    private ImageButton btnListen;
    private FrameLayout layout_speech;

    private RecognitionProgressView recognitionProgressView;

    private TextToSpeech textToSpeech;

    private SpeechRecognizer speechRecognizer;
    private edu.cmu.pocketsphinx.SpeechRecognizer recognizer;
    private Intent mSpeechRecognizerIntent;

    private AIService aiService;
    private static boolean isExcecuteText = false;

    private LocationManager locationManager;
    private double latitude, longitude;
    private OpenWeatherMapHelper weather;

    private Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private String deviceId;
    private boolean notchangesessionid = false;
    private String currentSessionId;

    private List<AIOutputContext> contexts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestPermission();

        init();

        mSpeechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        mSpeechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        mSpeechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault());

//        startRecognition();

        runRecognizerSetup();
    }

    private void init() {

        deviceId = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID);

        // Get phone's location
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Location location = locationManager.getLastKnownLocation(locationManager.NETWORK_PROVIDER);
        onLocationChanged(location);

        // setup UI Message
        mMessageRecycler = (RecyclerView) findViewById(R.id.reyclerview_message_list);

        layout_speech = findViewById(R.id.layout_speech);

        btnListen = findViewById(R.id.btnListen);
        recognitionProgressView = (RecognitionProgressView) findViewById(R.id.recognition_view);

        textToSpeech = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.ERROR) {
                    textToSpeech.setLanguage(Locale.getDefault());
                }
            }
        });

        messageList = new ArrayList<>();

        mMessageAdapter = new MessageListAdapter(this, messageList);

        mMessageRecycler.setAdapter(mMessageAdapter);
        mMessageRecycler.setLayoutManager(new LinearLayoutManager(this));

        messageList.add(new Message("Chào bạn, tôi có thể giúp gì cho bạn!", false, System.currentTimeMillis()));

        readCsvMessage();

        setUiRecognition(this.getApplicationContext());

        btnListen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(LOG_TAG, "onClick listener....");
                startRecognition();
            }
        });


        final AIConfiguration config = new AIConfiguration("73cf2510f55c425eb5f5d8bb20d6d3e7",
                AIConfiguration.SupportedLanguages.English,
                AIConfiguration.RecognitionEngine.System);
        aiService = AIService.getService(this, config);


    }

    private void setUiRecognition(Context context) {
        //setup weather
        weather = new OpenWeatherMapHelper(getString(R.string.OPEN_WEATHER_MAP_API_KEY));
        weather.setUnits(Units.METRIC);
        weather.setLang(Lang.VIETNAMESE);

        // setup Speech Recognition
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        recognitionProgressView.setSpeechRecognizer(speechRecognizer);
        recognitionProgressView.setRecognitionListener(new RecognitionListenerAdapter() {
            @Override
            public void onResults(Bundle results) {
                if (isExcecuteText) {
                    return;
                }
                finishRecognition();
                speechRecognizer.stopListening();

                ArrayList<String> matches = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                String text = matches.get(0);
                Log.d(LOG_TAG, "onResults: " + text);
                isExcecuteText = true;

                sendMessage(text, true);

                processing_text(text);

            }
        });

        recognitionProgressView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                finishRecognition();
                speechRecognizer.stopListening();

            }
        });

        int[] colors = {
                ContextCompat.getColor(this, R.color.color1),
                ContextCompat.getColor(this, R.color.color2),
                ContextCompat.getColor(this, R.color.color3),
                ContextCompat.getColor(this, R.color.color4),
                ContextCompat.getColor(this, R.color.color5)
        };

        int[] heights = {60, 76, 58, 80, 55};


        recognitionProgressView.setColors(colors);
        recognitionProgressView.setBarMaxHeightsInDp(heights);
        recognitionProgressView.setCircleRadiusInDp(6); // kich thuoc cham tron
        recognitionProgressView.setSpacingInDp(2); // khoang cach giua cac cham tron
        recognitionProgressView.setIdleStateAmplitudeInDp(8); // bien do dao dong cua cham tron
        recognitionProgressView.setRotationRadiusInDp(40); // kich thuoc vong quay cua cham tron
        recognitionProgressView.play();

    }

    /**
     * Start Speech Recognition
     */
    private void startRecognition() {
        Log.d(LOG_TAG, "start listener....");
        btnListen.setVisibility(View.GONE);

        recognitionProgressView.play();
        recognitionProgressView.setVisibility(View.VISIBLE);

        speechRecognizer.startListening(mSpeechRecognizerIntent);
        isExcecuteText = false;
    }

    /**
     * Finish Speech Recognition
     */
    private void finishRecognition() {

        btnListen.setVisibility(View.VISIBLE);

        recognitionProgressView.stop();
        recognitionProgressView.play();

        recognitionProgressView.setVisibility(View.GONE);
    }


    private void processing_text(final String text) {
        Log.d(LOG_TAG, "================= processing_text: " + text);

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AIRequest aiRequest = new AIRequest(text);
                    if (!notchangesessionid || currentSessionId == null) {
                        currentSessionId = deviceId + "#" + latitude + "-" + longitude;
                    }
                    aiRequest.setSessionId(currentSessionId);
                    if (contexts != null) {
                        List<AIContext> rqContexts = new ArrayList<>();
                        for (AIOutputContext oc : contexts) {
                            rqContexts.add(new MyAIContext(oc));
                        }
                        aiRequest.setContexts(rqContexts);
                    }

                    Log.d(LOG_TAG, "===== aiRequest:" + gson.toJson(aiRequest));
                    try {
                        AIResponse aiRes = aiService.textRequest(aiRequest);

                        Log.d(LOG_TAG, gson.toJson(aiRes));
                        String action = aiRes.getResult().getAction().toLowerCase();
                        Log.d(LOG_TAG, "===== action:" + action);
                        contexts = aiRes.getResult().getContexts();
                        String code = aiRes.getResult().getFulfillment().getData().get("code").toString().replace("\"", "");
                        try {
                            notchangesessionid = aiRes.getResult().getFulfillment().getData().get("notchangesessionid").getAsBoolean();
                            Log.d(LOG_TAG, "===== notchangesessionid: " + notchangesessionid);
                        } catch (Exception e) {

                        }
                        switch (action) {
                            case "input.unknown":
                                String textSpeech = aiRes.getResult().getFulfillment().getSpeech();
                                Log.d(LOG_TAG, "===== textSpeech:" + textSpeech);
                                textToSpeech.speak(textSpeech, TextToSpeech.QUEUE_FLUSH, null);
                                sendMessage(textSpeech, false);
                                break;
                            case "mp3":
                                Log.d(LOG_TAG, "======= code:" + code);
                                if (code.equals("1")) {
                                    Audio audio = gson.fromJson(
                                            aiRes.getResult().getFulfillment().getData().get("audios").getAsJsonArray().get(0).toString(), Audio.class);
                                    Intent intent = new Intent(Intent.ACTION_VIEW);
                                    intent.setData(Uri.parse(audio.getLink()));
                                    intent.setPackage("com.zing.mp3");
                                    startActivity(intent);
                                    sendMessage(audio.getAlias(), false);
                                } else {
                                    textSpeech = aiRes.getResult().getFulfillment().getSpeech();
                                    Log.d(LOG_TAG, "===== textSpeech:" + textSpeech);
                                    textToSpeech.speak(textSpeech, TextToSpeech.QUEUE_FLUSH, null);
                                    sendMessage(textSpeech, false);
                                }
                                break;
                            case "youtube":
                                Youtube video = gson.fromJson(
                                        aiRes.getResult().getFulfillment().getData().get("videos").getAsJsonArray().get(0).toString(), Youtube.class);
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setData(Uri.parse(video.getHref()));
                                intent.setPackage("com.google.android.youtube");
                                startActivity(intent);
                                sendMessage(video.getHref(), false);
                                break;
                            default:
                                if (text.toLowerCase().contains("thời tiết")) {
                                    weather();
                                } else if (text.toLowerCase().contains("tìm")) {
                                    search(text);
                                }
                        }

                    } catch (AIServiceException e) {
                        Log.e(LOG_TAG, e.getMessage(), e);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    isExcecuteText = false;
                }
            }
        });
        thread.start();
    }

    private void sendMessage(String text, boolean isUser) {

        messageList.add(new Message(text, isUser, System.currentTimeMillis()));
        try {
            mMessageAdapter.notifyDataSetChanged();
        } catch (Exception e) {

        }
        mMessageRecycler.smoothScrollToPosition(messageList.size() - 1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                writeCsvMessage();
            }
        }).start();
    }

    /**
     * Check permission
     */
    private void requestPermission() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        List<String> remainingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                remainingPermissions.add(permission);
            }
        }
        if (remainingPermissions.size() > 0) {
            requestPermissions(remainingPermissions.toArray(new String[remainingPermissions.size()]), 101);
        }
//        }
    }

    private void writeCsvMessage() {
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vnest_CA");
        if (!folder.exists()) {
            folder.mkdirs();
        }

        File csv = new File(folder, "message.csv");
        if (!csv.exists()) {
            try {
                csv.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        String data = "";
        for (Message m : messageList) {
            data += m.getMessage() + ";" + m.getCreatedAt() + ";" + String.valueOf(m.isSender()) + "\n";
        }
        Log.d("writeCsvMessage: ", data);

        FileWriter fw = null;
        try {

            fw = new FileWriter(csv.getAbsoluteFile());
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(data);
            bw.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Read data from file database csv
     */
    private void readCsvMessage() {

        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vnest_CA").getAbsoluteFile();

        if (folder.exists()) {

            File csv = new File(folder, "message.csv");

            if (csv.exists()) {

                BufferedReader br = null;
                try {
                    String m;
                    br = new BufferedReader(new FileReader(csv));
                    while ((m = br.readLine()) != null) {

                        String[] ms = m.split(";");
                        if (ms.length == 3) {
                            String message = ms[0];
                            long time = Long.parseLong(ms[1]);
                            boolean isUser = Boolean.valueOf(ms[2]);

                            if (!message.equals("Chào bạn, Tôi có thể giúp gì cho bạn!")) {
                                Log.d("readCsvMessage: ", message + " " + String.valueOf(isUser) + " " + String.valueOf(time));
                                messageList.add(new Message(message, isUser, time));
                            }
                        }
                    }
                    mMessageAdapter.notifyDataSetChanged();


                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (br != null) br.close();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }

            }


        }

    }

    public boolean onCreateOptionsMenu(Menu menu) {

        MenuInflater inflater = getMenuInflater();

        inflater.inflate(R.menu.menu_main, menu);

        return true;

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.delete:
                deleteMessage();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.d(LOG_TAG, "stop TRIGGER");
        //Start service
        Intent intent = new Intent(this, Trigger.class);
        stopService(intent);

//        if(isRecognitionSpeech){
//            //start Recognition Speech
//            startRecognition();
//        }

    }

    /**
     * Stop the recognizer.
     * Since cancel() does trigger an onResult() call,
     * we cancel the recognizer rather then stopping it.
     */
    @Override
    protected void onPause() {
        super.onPause();

        Log.d(LOG_TAG, "start TRIGGER");

        finishRecognition();
        speechRecognizer.stopListening();

        //Start service
        Intent intent = new Intent(this, Trigger.class);
        startService(intent);

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (speechRecognizer != null) {

            speechRecognizer.destroy();

        }

        //Start service
        Intent intent = new Intent(this, Trigger.class);
        stopService(intent);

    }

    private void deleteMessage() {
        messageList.clear();
        messageList.add(new Message("Chào bạn, Tôi có thể giúp gì cho bạn!", false, System.currentTimeMillis()));
        mMessageAdapter.notifyDataSetChanged();
        Toast.makeText(getApplicationContext(), " Xóa dữ liệu thành công", Toast.LENGTH_LONG).show();

    }

    private void search(String text) {
        if (text.contains("đường")) {

            String string_start = "đến";

            int start = text.indexOf(string_start) + string_start.length();
            int end = text.length();

            String location = text.substring(start, end);
            navigation(location);

            sendMessage("Tìm đường đi đến " + location, false);

        } else if (text.contains("gần")) {

            String string_start = "tìm";
            String string_end = "gần";

            int start = text.indexOf(string_start) + string_start.length();
            int end = text.indexOf(string_end);

            String location = text.substring(start, end);
            search_location(location);

            sendMessage("Tìm " + location + "gần nhất", false);
        } else {

            String string_start = "tìm";

            int start = text.indexOf(string_start) + string_start.length();
            int end = text.length();

            String key = text.substring(start, end);

            sendMessage("Search: " + key, false);

            search_google(key);
        }
    }

    /**
     * Search to the google by key search
     *
     * @param key: key search
     */
    private void search_google(String key) {

        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);

        intent.putExtra(SearchManager.QUERY, key);

        startActivity(intent);
    }

    /**
     * Search for the nearest your location
     *
     * @param location: address to find
     */
    private void search_location(String location) {
        Uri gmmIntentUri = Uri.parse("geo:0,0?q=" + location);
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.setPackage("com.google.android.apps.maps");
        startActivity(mapIntent);
    }

    private void navigation(String location) {
        location = location.replace(" ", "+");

        Uri gmmIntentUri = Uri.parse("google.navigation:q=" + location);
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.setPackage("com.google.android.apps.maps");
        startActivity(mapIntent);
    }

    private void weather() {

        final ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("please waiting....");
        progressDialog.show();

        weather.getCurrentWeatherByGeoCoordinates(latitude, longitude, new CurrentWeatherCallback() {
            @Override
            public void onSuccess(CurrentWeather currentWeather) {

                progressDialog.dismiss();

                Date timeSunrise = new Date(currentWeather.getSys().getSunrise() * 1000);
                Date timeSunset = new Date(currentWeather.getSys().getSunset() * 1000);

                DateFormat dateFormat = new SimpleDateFormat("hh:mm a");

                String sunrise = dateFormat.format(timeSunrise);
                String sunset = dateFormat.format(timeSunset);


                Log.v("Weather", "Coordinates: " + currentWeather.getCoord().getLat() + ", " + currentWeather.getCoord().getLon() + "\n"
                        + "Weather Description: " + currentWeather.getWeather().get(0).getDescription() + "\n"
                        + "Temperature: " + currentWeather.getMain().getTempMax() + "\n"
                        + "Wind Speed: " + currentWeather.getWind().getSpeed() + "\n"
                        + "City, Country: " + currentWeather.getName() + ", " + currentWeather.getSys().getCountry() + "\n"
                        + "Time sunrise: " + sunrise + "\n"
                        + "Time sunset: " + sunset + "\n"
                );

                String location = currentWeather.getName() + ", " + currentWeather.getSys().getCountry();
                String description = currentWeather.getWeather().get(0).getDescription();
                String wind = String.valueOf(currentWeather.getWind().getSpeed());
                String tempMax = String.valueOf(currentWeather.getMain().getTempMax());
                String humidity = String.valueOf(currentWeather.getMain().getHumidity());

                show_weather(location, description, tempMax, wind, humidity, sunrise, sunset);
            }

            @Override
            public void onFailure(Throwable throwable) {
                progressDialog.dismiss();
                sendMessage("Lỗi, Không thế tìm kiếm thời tiết tại vị trí của bạn.", false);
                Log.d("Weather", throwable.getMessage(), throwable);
            }
        });

    }


    /**
     * Show dialog weather in location
     *
     * @param location
     * @param description
     * @param tempMax
     * @param wind
     * @param humidity
     * @param sunrise
     * @param sunset
     */
    private void show_weather(String location, String description, String tempMax, String
            wind, String humidity, String sunrise, String sunset) {

        String w = "Thời tiết " + location + " : " + description + " " + tempMax + "\u2103";

        sendMessage(w, false);

        String textSpeech = "Thời tiết " + location + " : " + description + " " + tempMax + "độ xê";
        textToSpeech.speak(textSpeech, TextToSpeech.QUEUE_FLUSH, null);


        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.weather);

        dialog.setContentView(R.layout.weather);

        TextView tvLocation, tvDescription, tvTempMax, tvWind, tvHumidity, tvSunrise, tvSunset;

        tvLocation = dialog.findViewById(R.id.tvLocation);
        tvDescription = dialog.findViewById(R.id.tvDescription);
        tvTempMax = dialog.findViewById(R.id.tvTempMax);
        tvWind = dialog.findViewById(R.id.tvWind);
        tvHumidity = dialog.findViewById(R.id.tvHumidity);
        tvSunrise = dialog.findViewById(R.id.tvSunrise);
        tvSunset = dialog.findViewById(R.id.tvSunset);


        tvLocation.setText(location);
        tvDescription.setText(description);
        tvTempMax.setText(tempMax);
        tvWind.setText(wind);
        tvHumidity.setText(humidity);
        tvSunrise.setText(sunrise);
        tvSunset.setText(sunset);

        dialog.show();
    }

    @Override
    public void onLocationChanged(Location location) {
        latitude = location.getLatitude();
        longitude = location.getLongitude();

        Log.d("onLocationChanged", String.valueOf(latitude) + " " + String.valueOf(longitude));
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {

    }

    @Override
    public void onProviderEnabled(String s) {

    }

    @Override
    public void onProviderDisabled(String s) {

    }

    private void runRecognizerSetup() {
        // Recognizer initialization is a time-consuming and it involves IO,
        // so we execute it in async task
        new AsyncTask<Void, Void, Exception>() {
            @Override
            protected Exception doInBackground(Void... params) {
                try {
                    Assets assets = new Assets(MainActivity.this);
                    File assetDir = assets.syncAssets();
                    setupRecognizer(assetDir);
                } catch (IOException e) {
                    return e;
                }
                return null;
            }

            @Override
            protected void onPostExecute(Exception result) {
                startRecognition();
            }
        }.execute();
    }

    private void setupRecognizer(File assetsDir) throws IOException {
        // The recognizer can be configured to perform multiple searches
        // of different kind and switch between them

        recognizer = SpeechRecognizerSetup.defaultSetup()
                .setAcousticModel(new File(assetsDir, "en-us-ptm"))
                .setDictionary(new File(assetsDir, "cmudict-en-us.dict"))

                .setRawLogDir(assetsDir) // To disable logging of raw audio comment out this call (takes a lot of space on the device)

                .getRecognizer();
        recognizer.addListener(this);

        /** In your application you might not need to add all those searches.
         * They are added here for demonstration. You can leave just one.
         */

        // Create keyword-activation search.
        recognizer.addKeyphraseSearch(KWS_SEARCH, KEYPHRASE);
    }

    @Override
    public void onBeginningOfSpeech() {

    }

    @Override
    public void onEndOfSpeech() {

    }

    @Override
    public void onPartialResult(Hypothesis hypothesis) {
        if (hypothesis == null)
            return;

        String text = hypothesis.getHypstr();
        if (text.equals(KEYPHRASE))
            startRecognition();

    }

    @Override
    public void onResult(Hypothesis hypothesis) {

    }

    @Override
    public void onError(Exception e) {

    }

    @Override
    public void onTimeout() {

    }
}
