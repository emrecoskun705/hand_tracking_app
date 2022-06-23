package tr.com.emrecoskun.myhandapp;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.mediapipe.solutioncore.CameraInput;
import com.google.mediapipe.solutioncore.SolutionGlSurfaceView;
import com.google.mediapipe.solutioncore.VideoInput;
import com.google.mediapipe.solutions.hands.Hands;
import com.google.mediapipe.solutions.hands.HandsOptions;
import com.google.mediapipe.solutions.hands.HandsResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private Hands hands;
    // Run the pipeline and the model inference on GPU or CPU.
    private static final boolean RUN_ON_GPU = true;

    private static boolean isLearn;

    private enum InputSource {
        UNKNOWN,
        IMAGE,
        VIDEO,
        CAMERA,
    }
    private InputSource inputSource = InputSource.UNKNOWN;

    // Image demo UI and image loader components.
    private ActivityResultLauncher<Intent> imageGetter;
    private HandsResultImageView imageView;
    // Video demo UI and video loader components.
    private VideoInput videoInput;
    private ActivityResultLauncher<Intent> videoGetter;
    // Live camera demo UI and camera components.
    private CameraInput cameraInput;

    private SolutionGlSurfaceView<HandsResult> glSurfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupStaticImageDemoUiComponents();
        setupLiveDemoUiComponentsForLearn();
        setupLiveDemoUiComponentsForCalculator();
        findViewById(R.id.calculator).setVisibility(View.INVISIBLE);
        getSupportActionBar().setTitle("Emre Ali App");

    }


    @Override
    protected void onResume() {
        super.onResume();
        if (inputSource == InputSource.CAMERA) {
            // Restarts the camera and the opengl surface rendering.
            cameraInput = new CameraInput(this);
            cameraInput.setNewFrameListener(textureFrame -> hands.send(textureFrame));
            glSurfaceView.post(this::startCamera);
            glSurfaceView.setVisibility(View.VISIBLE);
        } else if (inputSource == InputSource.VIDEO) {
            videoInput.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (inputSource == InputSource.CAMERA) {
            glSurfaceView.setVisibility(View.GONE);
            cameraInput.close();
        } else if (inputSource == InputSource.VIDEO) {
            videoInput.pause();
        }
    }

    private Bitmap downscaleBitmap(Bitmap originalBitmap) {
        double aspectRatio = (double) originalBitmap.getWidth() / originalBitmap.getHeight();
        int width = imageView.getWidth();
        int height = imageView.getHeight();
        if (((double) imageView.getWidth() / imageView.getHeight()) > aspectRatio) {
            width = (int) (height * aspectRatio);
        } else {
            height = (int) (width / aspectRatio);
        }
        return Bitmap.createScaledBitmap(originalBitmap, width, height, false);
    }

    private Bitmap rotateBitmap(Bitmap inputBitmap, InputStream imageData) throws IOException {
        int orientation =
                new ExifInterface(imageData)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            return inputBitmap;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            default:
                matrix.postRotate(0);
        }
        return Bitmap.createBitmap(
                inputBitmap, 0, 0, inputBitmap.getWidth(), inputBitmap.getHeight(), matrix, true);
    }

    /** Sets up the UI components for the static image demo. */
    private void setupStaticImageDemoUiComponents() {
        // The Intent to access gallery and read images as bitmap.
        imageGetter =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            Intent resultIntent = result.getData();
                            if (resultIntent != null) {
                                if (result.getResultCode() == RESULT_OK) {
                                    Bitmap bitmap = null;
                                    try {
                                        bitmap =
                                                downscaleBitmap(
                                                        MediaStore.Images.Media.getBitmap(
                                                                this.getContentResolver(), resultIntent.getData()));
                                    } catch (IOException e) {
                                        Log.e(TAG, "Bitmap reading error:" + e);
                                    }
                                    try {
                                        InputStream imageData =
                                                this.getContentResolver().openInputStream(resultIntent.getData());
                                        bitmap = rotateBitmap(bitmap, imageData);
                                    } catch (IOException e) {
                                        Log.e(TAG, "Bitmap rotation error:" + e);
                                    }
                                    if (bitmap != null) {
                                        hands.send(bitmap);
                                    }
                                }
                            }
                        });
        Button loadImageButton = findViewById(R.id.button_load_picture);
        loadImageButton.setOnClickListener(
                v -> {
                    if (inputSource != InputSource.IMAGE) {
                        stopCurrentPipeline();
                        setupStaticImageModePipeline();
                    }
                    // Reads images from gallery.
                    Intent pickImageIntent = new Intent(Intent.ACTION_PICK);
                    pickImageIntent.setDataAndType(MediaStore.Images.Media.INTERNAL_CONTENT_URI, "image/*");
                    imageGetter.launch(pickImageIntent);
                });
        imageView = new HandsResultImageView(this);
    }

    /** Sets up core workflow for static image mode. */
    private void setupStaticImageModePipeline() {
        this.inputSource = InputSource.IMAGE;
        // Initializes a new MediaPipe Hands solution instance in the static image mode.
        hands =
                new Hands(
                        this,
                        HandsOptions.builder()
                                .setStaticImageMode(true)
                                .setMaxNumHands(2)
                                .setRunOnGpu(RUN_ON_GPU)
                                .build());

        // Connects MediaPipe Hands solution to the user-defined HandsResultImageView.
        hands.setResultListener(
                handsResult -> {
                    logWristLandmark(handsResult);
                    imageView.setHandsResult(handsResult);
                    runOnUiThread(() -> imageView.update());
                });
        hands.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Hands error:" + message));

        // Updates the preview layout.
        FrameLayout frameLayout = findViewById(R.id.preview_display_layout);
        frameLayout.removeAllViewsInLayout();
        imageView.setImageDrawable(null);
        frameLayout.addView(imageView);
        imageView.setVisibility(View.VISIBLE);
    }

    /** Sets up the UI components for the live demo with camera input. */
    private void setupLiveDemoUiComponentsForLearn() {
        Button startCameraButton = findViewById(R.id.button_learn);
        startCameraButton.setOnClickListener(
                v -> {
                    getSupportActionBar().setTitle("Learn");
                    isLearn = true;
                    findViewById(R.id.calculator).setVisibility(View.INVISIBLE);
                    findViewById(R.id.calculator_result_text).setVisibility(View.INVISIBLE);
                    findViewById(R.id.learn_result_text).setVisibility(View.VISIBLE);
                    stopCurrentPipeline();
                    setupStreamingModePipeline(InputSource.CAMERA);
                });
    }

    /** Sets up the UI components for the live demo with camera input. */
    private void setupLiveDemoUiComponentsForCalculator() {
        Button startCameraButton = findViewById(R.id.button_start_camera);
        startCameraButton.setOnClickListener(
                v -> {
                    getSupportActionBar().setTitle("Calculator");
                    isLearn = false;
                    findViewById(R.id.calculator).setVisibility(View.VISIBLE);
                    findViewById(R.id.calculator_result_text).setVisibility(View.VISIBLE);
                    findViewById(R.id.learn_result_text).setVisibility(View.INVISIBLE);
                    stopCurrentPipeline();
                    setupStreamingModePipeline(InputSource.CAMERA);
                });
    }

    /** Sets up core workflow for streaming mode. */
    private void setupStreamingModePipeline(InputSource inputSource) {
        this.inputSource = inputSource;
        // Initializes a new MediaPipe Hands solution instance in the streaming mode.
        hands =
                new Hands(
                        this,
                        HandsOptions.builder()
                                .setStaticImageMode(false)
                                .setMaxNumHands(2)
                                .setRunOnGpu(RUN_ON_GPU)
                                .build());
        hands.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Hands error:" + message));

        if (inputSource == InputSource.CAMERA) {
            cameraInput = new CameraInput(this);
            cameraInput.setNewFrameListener(textureFrame -> hands.send(textureFrame));
        } else if (inputSource == InputSource.VIDEO) {
            videoInput = new VideoInput(this);
            videoInput.setNewFrameListener(textureFrame -> hands.send(textureFrame));
        }

        // Initializes a new Gl surface view with a user-defined HandsResultGlRenderer.
        glSurfaceView =
                new SolutionGlSurfaceView<>(this, hands.getGlContext(), hands.getGlMajorVersion());
        glSurfaceView.setSolutionResultRenderer(new HandsResultGlRenderer());
        glSurfaceView.setRenderInputImage(true);
        hands.setResultListener(
                handsResult -> {

                    glSurfaceView.setRenderData(handsResult);
                    glSurfaceView.requestRender();
                    logWristLandmark(handsResult);
                });

        // The runnable to start camera after the gl surface view is attached.
        // For video input source, videoInput.start() will be called when the video uri is available.
        if (inputSource == InputSource.CAMERA) {
            glSurfaceView.post(this::startCamera);
        }

        // Updates the preview layout.
        FrameLayout frameLayout = findViewById(R.id.preview_display_layout);
        imageView.setVisibility(View.GONE);
        frameLayout.removeAllViewsInLayout();
        frameLayout.addView(glSurfaceView);
        glSurfaceView.setVisibility(View.VISIBLE);
        frameLayout.requestLayout();
    }

    private void startCamera() {
        cameraInput.start(
                this,
                hands.getGlContext(),
                CameraInput.CameraFacing.FRONT,
                glSurfaceView.getWidth(),
                glSurfaceView.getHeight());
    }

    private void stopCurrentPipeline() {
        if (cameraInput != null) {
            cameraInput.setNewFrameListener(null);
            cameraInput.close();
        }
        if (videoInput != null) {
            videoInput.setNewFrameListener(null);
            videoInput.close();
        }
        if (glSurfaceView != null) {
            glSurfaceView.setVisibility(View.GONE);
        }
        if (hands != null) {
            hands.close();
        }
    }


    private void logWristLandmark(HandsResult result) {
        if (isLearn) {
            sendMLData(result);
        } else {
            calculator(result);
        }


    }

    boolean isTrueSend = true;
    private void sendMLData(HandsResult result) {
        if(!isTrueSend) {
            return;
        }
        isTrueSend = false;
        new TimeTask(new OnBooleanReceive() {
            @Override
            public void onReceive(boolean data) {
                isTrueSend = data;
            }
        }).execute(2000);

        if(result == null) {
            return;
        }
        if (result.multiHandLandmarks().isEmpty()) {
            return;
        }
        try {
            RequestQueue requestQueue = Volley.newRequestQueue(this);

            String URL = "http://192.168.43.103:8000/get-ml/";
            JSONObject jsonBody = new JSONObject();
            float wrist_x = result.multiHandLandmarks().get(0).getLandmarkList().get(0).getX();
            float wrist_y = result.multiHandLandmarks().get(0).getLandmarkList().get(0).getY();
            float wrist_z = result.multiHandLandmarks().get(0).getLandmarkList().get(0).getZ();
            calculator(result);
            for(int i=1; i<21; i++) {
                JSONArray coordinates = new JSONArray();
                coordinates.put(result.multiHandLandmarks().get(0).getLandmarkList().get(i).getX() - wrist_x);
                coordinates.put(result.multiHandLandmarks().get(0).getLandmarkList().get(i).getY() - wrist_y);
                coordinates.put(result.multiHandLandmarks().get(0).getLandmarkList().get(i).getZ() - wrist_z);
                jsonBody.put("" + i, coordinates);
            }
            jsonBody.put("21", result.multiHandLandmarks().get(0).getLandmarkCount());
            final String requestBody = jsonBody.toString();

            StringRequest stringRequest = new StringRequest(Request.Method.POST, URL, new Response.Listener<String>() {
                @Override
                public void onResponse(String response) {
                    Log.i("VOLLEY1", response.split("'")[1]);
                    ((TextView)findViewById(R.id.learn_result_text)).setText(response.split("'")[1]);
                }
            }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    Log.e("VOLLEY", error.toString());
                }
            }) {
                @Override
                public String getBodyContentType() {
                    return "application/json; charset=utf-8";
                }

                @Override
                public byte[] getBody() throws AuthFailureError {
                    try {
                        return requestBody == null ? null : requestBody.getBytes("utf-8");
                    } catch (UnsupportedEncodingException uee) {
                        VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s", requestBody, "utf-8");
                        return null;
                    }
                }


            };
            requestQueue.add(stringRequest);

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // Watches the 8 and 12 hand landmarks
    int counter = 0;
    private void calculator(HandsResult result) {
        if(result == null) {
            return;
        }
        if (result.multiHandLandmarks().isEmpty()) {
            return;
        }

        int width = result.inputBitmap().getWidth();


        float eight_x = result.multiHandLandmarks().get(0).getLandmarkList().get(8).getX();
        float eight_y = result.multiHandLandmarks().get(0).getLandmarkList().get(8).getY();

        float twelve_x = result.multiHandLandmarks().get(0).getLandmarkList().get(12).getX();

        if(counter != 0 && counter++>20) {
            counter = 0;
        }

        // if 8 point and 12 point landmark is close to each other than 20 then it will work with some time using counter
        if(Math.abs(eight_x * width - twelve_x * width) <= 20 && counter == 0 ) {
            counter = 1;
            TextView myText = (TextView)(findViewById(R.id.calculator_result_text));
            // location of each signs and numbers
            if(eight_x < 0.3f) {
                if(eight_y < 0.1666f) {
                    updateCalculatorResultText(myText, "%", false);
                } else if(eight_y < 0.3332f) {
//                    updateCalculatorResultText(myText, "x", false);
                    alertCalculator("This operation is not used yet.");
                } else if(eight_y < 0.4998f) {
                    updateCalculatorResultText(myText, "7", true);
                } else if(eight_y < 0.6664f) {
                    updateCalculatorResultText(myText, "4", true);
                } else if(eight_y < 0.8330f) {
                    updateCalculatorResultText(myText, "1", true);
                } else {
//                    updateCalculatorResultText(myText, "+/-", false);
                    alertCalculator("This operation is not used yet.");
                }
            } else if (eight_x < 0.5f) {
                if(eight_y < 0.1666f) {
                    updateCalculatorResultText(myText, "CE", false);
                } else if(eight_y < 0.3332f) {
//                    updateCalculatorResultText(myText, "x2", false);
                    alertCalculator("This operation is not used yet.");
                } else if(eight_y < 0.4998f) {
                    updateCalculatorResultText(myText, "8", true);
                } else if(eight_y < 0.6664f) {
                    updateCalculatorResultText(myText, "5", true);
                } else if(eight_y < 0.8330f) {
                    updateCalculatorResultText(myText, "2", true);
                } else {
                    updateCalculatorResultText(myText, "0", true);
                }
            } else if(eight_x < 0.7f) {
                if(eight_y < 0.1666f) {
                    updateCalculatorResultText(myText, "c", false);
                } else if(eight_y < 0.3332f) {
//                    updateCalculatorResultText(myText, "x", false);
                    alertCalculator("This operation is not used yet.");
                } else if(eight_y < 0.4998f) {
                    updateCalculatorResultText(myText, "9", true);
                } else if(eight_y < 0.6664f) {
                    updateCalculatorResultText(myText, "6", true);
                } else if(eight_y < 0.8330f) {
                    updateCalculatorResultText(myText, "3", true);
                } else {
                    updateCalculatorResultText(myText, ".", true);
                }
            } else {
                if(eight_y < 0.1666f) {
                    updateCalculatorResultText(myText, "delete", false);
                } else if(eight_y < 0.3332f) {
                    updateCalculatorResultText(myText, "/", false);
                } else if(eight_y < 0.4998f) {
                    updateCalculatorResultText(myText, "*", false);
                } else if(eight_y < 0.6664f) {
                    updateCalculatorResultText(myText, "-", false);
                } else if(eight_y < 0.8330f) {
                    updateCalculatorResultText(myText, "+", false);
                } else {
                    updateCalculatorResultText(myText, "=", true);
                }
            }

        }


    }

    public void updateCalculatorResultText(TextView view, String character, boolean isNumber) {
        view.post(new Runnable() {
            @Override
            public void run() {
                String getText = view.getText().toString();
                int lenText = getText.length();

                if (character.equals("c")) {
                    view.setText("");
                    return;
                }

                if(!isNumber && checkOperation(getText)) {
                    alertCalculator("There must be 1 operation at a time.");
                    return;
                }


                if(character.equals("=") && lenText > 0 && checkOperation(getText)) {
                    String[] arr = getText.split(" ");
                    double num1 = Double.parseDouble(arr[0]);
                    double num2 = Double.parseDouble(arr[2]);
                    String operation = arr[1];
                    if(operation.equals("%")) {
                        num1 = num1 % num2;
                    } else if(operation.equals("/")) {
                        num1 /= num2;
                    } else if(operation.equals("*")) {
                        num1 *= num2;
                    } else if(operation.equals("+")) {
                        num1 += num2;
                    } else if(operation.equals("-")) {
                        num1 -= num2;
                    }

                    view.setText(""+num1);
                    return;
                }
                if(character.equals("delete")) {

                    if (getText.charAt(lenText - 1) == ' ') {
                        view.setText(getText.substring(0, lenText - 3));
                    } else {
                        view.setText(getText.substring(0, lenText - 1));
                    }
                }  else {
                    view.setText(getText +  (isNumber ? character : " " + character + " "));
                }

            }
        });
    }

    private boolean checkOperation(String getText) {
        return (getText.contains("%") || getText.contains("/") || getText.contains("*") || getText.contains("+") || getText.contains("-"));
    }

    private void alertCalculator(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle("Invalid Operation.");
        builder.setMessage(message);
        builder.show();
    }

    public interface OnBooleanReceive {
        void onReceive(boolean data);
    }

    class TimeTask extends AsyncTask<Integer, Boolean, Boolean> {

        OnBooleanReceive myOnBooleanReceive;

        public TimeTask(OnBooleanReceive listener) {
            this.myOnBooleanReceive = listener;
        }

        @Override
        protected Boolean doInBackground(Integer... integers) {

            try
            {
                Thread.sleep(integers[0]);//Your Interval after which you want to refresh the screen
            }
            catch (InterruptedException e)
            {
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean aBoolean) {
            if(myOnBooleanReceive != null) {
                myOnBooleanReceive.onReceive(aBoolean);
            }

        }
    }
}