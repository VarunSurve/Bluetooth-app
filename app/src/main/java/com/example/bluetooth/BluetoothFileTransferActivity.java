package com.example.bluetooth;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class BluetoothFileTransferActivity extends AppCompatActivity {
    private static final String TAG = "BluetoothFileTransfer";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice selectedDevice;
    private BluetoothSocket socket;
    private ConnectedThread connectedThread;
    private Uri fileUri;

    private TextView statusText;
    private Button sendFileButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        sendFileButton = findViewById(R.id.send_file_button);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        requestPermissionsForAndroid12();
        enableBluetooth();

        sendFileButton.setOnClickListener(v -> {
            if (selectedDevice != null && fileUri != null) {
                new ConnectThread(selectedDevice).start();
            } else {
                Toast.makeText(this, "Select device and file first", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void requestPermissionsForAndroid12() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, 1);
        }
    }

    private void enableBluetooth() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        } else {
            selectBluetoothDevice();
        }
    }

    private void selectBluetoothDevice() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found", Toast.LENGTH_LONG).show();
            return;
        }

        String[] deviceNames = new String[pairedDevices.size()];
        BluetoothDevice[] devicesArray = pairedDevices.toArray(new BluetoothDevice[0]);

        for (int i = 0; i < devicesArray.length; i++) {
            deviceNames[i] = devicesArray[i].getName() + "\n" + devicesArray[i].getAddress();
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Select Bluetooth Device")
                .setItems(deviceNames, (dialog, which) -> {
                    selectedDevice = devicesArray[which];
                    statusText.setText("Selected Device: " + selectedDevice.getName());
                    selectFile();
                })
                .show();
    }

    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    fileUri = result.getData().getData();
                    statusText.setText("File Selected: " + fileUri.getPath());
                }
            }
    );

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp = null;
            mmDevice = device;

            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket creation failed", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            bluetoothAdapter.cancelDiscovery();
            try {
                mmSocket.connect();
                runOnUiThread(() -> statusText.setText("Connected to " + mmDevice.getName()));
                connectedThread = new ConnectedThread(mmSocket);
                connectedThread.start();

                if (fileUri != null) {
                    connectedThread.writeFile(fileUri);
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not connect to device", e);
                runOnUiThread(() -> statusText.setText("Connection successsful"));
                try {
                    mmSocket.close();
                } catch (IOException ex) {
                    Log.e(TAG, "Could not close the client socket", ex);
                }
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error getting streams", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void writeFile(Uri fileUri) {
            try {
                ContentResolver contentResolver = getContentResolver();
                InputStream fileInputStream = contentResolver.openInputStream(fileUri);

                if (fileInputStream == null) {
                    Log.e(TAG, "File InputStream is null");
                    return;
                }

                byte[] buffer = new byte[1024];
                int bytesRead;

                runOnUiThread(() -> statusText.setText("Sending file..."));

                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    mmOutStream.write(buffer, 0, bytesRead);
                }

                fileInputStream.close();
                mmOutStream.flush();
                runOnUiThread(() -> statusText.setText("File sent successfully!"));

            } catch (IOException e) {
                Log.e(TAG, "Error sending file", e);
                runOnUiThread(() -> statusText.setText("File transfer failed"));
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket", e);
            }
        }
    }
}
