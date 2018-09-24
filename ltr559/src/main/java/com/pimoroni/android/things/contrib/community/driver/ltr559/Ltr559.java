package com.pimoroni.android.things.contrib.community.driver.ltr559;

import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.things.pio.I2cDevice;
import com.google.android.things.pio.PeripheralManager;

import java.io.IOException;
import java.util.Arrays;

public class Ltr559 implements AutoCloseable {
    private static final String TAG = Ltr559.class.getSimpleName();

    public static final String CHIP_VENDOR = "LiteOn";
    public static final String CHIP_NAME = "LTR559";

    // Part number 0b11110000 (0x9) and revision 0b00001111 (0x2)
    private static final byte PART_ID = (byte)0x92;
    private static final byte MANUFACTURER_ID = (byte)0x05;
    private static final int RESET_PERIOD_MILLISECONDS = 50;

    private static final int REG_ALS_CONTROL     = 0x80;
    private static final int REG_PS_CONTROL      = 0x81;
    private static final int REG_PS_LED          = 0x82;
    private static final int REG_PS_N_PULSES     = 0x83;
    private static final int REG_PS_MEAS_RATE    = 0x84;
    private static final int REG_ALS_MEAS_RATE   = 0x85;
    private static final int REG_PART_ID         = 0x86;
    private static final int REG_MANUFACTURER_ID = 0x87;
    private static final int REG_ALS_DATA        = 0x88;
    private static final int REG_ALS_PS_STATUS   = 0x8c;
    private static final int REG_PS_DATA         = 0x8d;
    private static final int REG_INTERRUPT       = 0x8f;
    private static final int REG_PS_THRESHOLD    = 0x90;
    private static final int REG_PS_OFFSET       = 0x94;
    private static final int REG_ALS_THRESHOLD   = 0x97;
    private static final int REG_INTERRUPT_PERSIST = 0x9e;

    public static final int I2C_ADDRESS = 0x23;

    private static final int ch0_c[] = {17743, 42785, 5926, 0};
    private static final int ch1_c[] = {-11059, 19548, -1185, 0};

    private I2cDevice device;
    private byte partId;
    private byte manufacturerId;

    private int ps0;
    private int als0;
    private int als1;
    private double lux;

    /**
     * Initialise the Ltr559.
     *
     * @param bus i2c bus device.
     * @throws IOException
     */
    public Ltr559(@NonNull final String bus) throws IOException {
        final PeripheralManager peripheralManager = PeripheralManager.getInstance();
        this.device = peripheralManager.openI2cDevice(bus, I2C_ADDRESS);
        this.connect();
    }

    /**
     * Get a calculated lux value from the sensor.
     *
     * @return light amount in lux.
     * @throws IOException
     */
    public double getLux() throws IOException {
        this.updateSensor();
        return this.lux;
    }

    /**
     * Get the raw proximity value from the sensor.
     *
     * @return the proximity value.
     * @throws IOException
     */
    public int getProximity() throws IOException {
        this.updateSensor();
        return this.ps0;
    }

    /**
     * Set proximity sensor update rate.
     *
     * @param rate update period in milliseconds. One of: 10, 50, 70, 100, 200, 500, 1000 or 2000.
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public void setProximityRate(int rate)
            throws IOException, IllegalArgumentException {
        final int bitValue = Arrays.asList(10, 50, 70, 100, 200, 500, 1000, 2000).indexOf(rate);
        if(bitValue == -1){
            throw new IllegalArgumentException(
                    String.format("Invalid value supplied for rate: %d.", rate));
        }
        setBits(REG_PS_MEAS_RATE, 0x0f, 0, bitValue);
    }

    /**
     * Set light sensor options.
     *
     * @param active status of the light sensor.
     * @param gain gain multiplier, one of: 1, 2, 4, 8, 48 or 96.
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public void setLightOptions(boolean active, int gain)
            throws IOException, IllegalArgumentException {
        final int bitValue = Arrays.asList(1, 2, 4, 8, 48, 96).indexOf(gain);
        if(bitValue == -1){
            throw new IllegalArgumentException(
                    String.format("Invalid value supplied for gain: %d.", gain));
        }
        setBits(REG_ALS_CONTROL, 0b00000001, 0, active ? 1 : 0);
        setBits(REG_ALS_CONTROL, 0b00011100, 2, bitValue);
    }

    /**
     * Set light sensor measurement rate.
     *
     * @param rate measurement rate in milliseconds, one of: 50, 100, 200, 500, 1000, 2000
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public void setLightRate(int rate)
            throws IOException, IllegalArgumentException {
        final int bitValue = Arrays.asList(50, 100, 200, 500, 1000, 2000).indexOf(rate);
        if(bitValue == -1){
            throw new IllegalArgumentException(
                    String.format("Invalid value supplied for rate: %d.", rate));
        }
        setBits(REG_ALS_MEAS_RATE, 0b00000111, 0, bitValue);
    }

    /**
     * Set light sensor integration time.
     *
     * @param time time, in milliseconds, one of: 50, 100, 150, 200, 250, 300, 350 or 400
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public void setLightIntegrationTime(int time)
            throws IOException, IllegalArgumentException {
        final int bitValue = Arrays.asList(100, 50, 200, 400, 150, 250, 300, 350).indexOf(time);
        if(bitValue == -1){
            throw new IllegalArgumentException(
                    String.format("Invalid value supplied for timeMs: %d.", time));
        }
        setBits(REG_ALS_MEAS_RATE, 0b00111000, 3, bitValue);
    }

    /**
     * Set up the proximity sensor.
     *
     * @param active status of the proximity sensor.
     * @param saturationIndicator whether the saturation indicator bit should be set.
     * @throws IOException
     */
    public void setProximityControl(boolean active, boolean saturationIndicator)
            throws IOException {
        setBits(REG_PS_CONTROL, 0b00100000, 5, saturationIndicator ? 1 : 0); // Saturation Indicator Enable
        setBits(REG_PS_CONTROL, 0b00000011, 0, active ? 0b11 : 0); // Active
    }

    /**
     * Set up the proximity LED behaviour.
     *
     * @param current the LED current in mA: 5, 10, 20, 50 or 100
     * @param dutyCycle the LED pulse duty cycle: 25, 50, 75 or 100
     * @param pulseFreq the LED pulse frequency in kHz: 30, 40, 50, 60, 70, 80, 90 or 100
     * @param numPulses number of pulses the LED makes each cycle: 0-31
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public void setProximityLED(int current, int dutyCycle, int pulseFreq, int numPulses)
            throws IOException, IllegalArgumentException {
        int bitValue;
        bitValue = Arrays.asList(30, 40, 50, 60, 70, 80, 90, 100).indexOf(pulseFreq);
        if(bitValue == -1){
            throw new IllegalArgumentException(
                    String.format("Invalid value supplied for pulseFreq: %d.", pulseFreq));
        }
        setBits(REG_PS_LED, 0b11100000, 5, bitValue);

        bitValue = Arrays.asList(25, 50, 75, 100).indexOf(dutyCycle);
        if(bitValue == -1){
            throw new IllegalArgumentException(
                    String.format("Invalid value supplied for dutyCycle: %d.", dutyCycle));
        }
        setBits(REG_PS_LED, 0b00011000, 3, bitValue);

        bitValue = Arrays.asList(5, 10, 20, 50, 100).indexOf(current);
        if(bitValue == -1){
            throw new IllegalArgumentException(
                    String.format("Invalid value supplied for current: %d.", current));
        }
        setBits(REG_PS_LED, 0b00000111, 0, bitValue);

        setBits(REG_PS_N_PULSES, 0b00001111, 0, numPulses);
    }

    public void setProximityThreshold(short lower, short upper)
            throws IOException {
        this.device.writeRegWord(REG_PS_THRESHOLD + 0, lower);
        this.device.writeRegWord(REG_PS_THRESHOLD + 2, upper);

    }

    public void setLightThreshold(short lower, short upper)
            throws IOException {
        this.device.writeRegWord(REG_ALS_THRESHOLD + 0, lower);
        this.device.writeRegWord(REG_ALS_THRESHOLD + 2, upper);

    }

    /**
     * Reset the sensor.
     *
     * @throws IOException
     */
    public void reset() throws IOException {
        this.setBits(REG_ALS_CONTROL, 0b00000010, 1, 1);

        while(this.getBits(REG_ALS_CONTROL, 0b00000010, 1) != 0) {
            SystemClock.sleep(RESET_PERIOD_MILLISECONDS);
        }
    }

    @Override
    public void close() throws IOException {
        if (device != null) {
            try {
                device.close();
            } finally {
                device = null;
            }
        }
    }

    /**
     * Connect to the sensor and set default values.
     *
     * @throws IOException
     */
    private void connect() throws IOException {
        if (this.device == null) {
            throw new IllegalStateException("");
        }
        this.manufacturerId = this.device.readRegByte(REG_MANUFACTURER_ID);
        this.partId = this.device.readRegByte(REG_PART_ID);

        Log.d(TAG, String.format(" 0x%02X 0x%02X.", this.partId & 0xFF, this.manufacturerId & 0xFF));

        if(this.partId != PART_ID || this.manufacturerId != MANUFACTURER_ID) {
            throw new IllegalStateException(String.format("%s %s is not found 0x%02X 0x%02X.",
                    CHIP_VENDOR, CHIP_NAME, this.partId & 0xFF, this.manufacturerId & 0xFF));
        }

        this.reset();

        setProximityLED(50, 100, 30, 1);

        setLightOptions(true, 4);

        setProximityControl(true, true);

        setLightIntegrationTime(100); // 100ms
        setProximityRate(100); // 100ms
        setLightRate(100); // 100ms

        setProximityThreshold((short)0, (short)2047);
        setLightThreshold((short)0, (short)65535);


        byte[] offset = new byte[2];

        this.device.writeRegBuffer(REG_PS_OFFSET, offset, 2);

        setBits(REG_INTERRUPT, 0b00000011, 0, 0b11); // als+ps interrupts
    }

    /**
     * Update the internal proximity and lux states from the sensor.
     *
     * @throws IOException
     */
    public void updateSensor() throws IOException {
        int ratio = 1000;
        int ch_idx = 3;

        byte status = this.device.readRegByte(REG_ALS_PS_STATUS);
        boolean psInt = (status & 0b00000011) > 0; // Test the ALS Interrupt and Data bits
        boolean alsInt = (status & 0b00001100) > 0; // Test the PS Interrupt and Data bits

        if (psInt) {
            this.ps0 = this.device.readRegWord(REG_PS_DATA) & 0x7ff;
        }

        if (alsInt) {
            this.als0 = this.device.readRegWord(REG_ALS_DATA);
            this.als1 = this.device.readRegWord(REG_ALS_DATA + 2);

            if (this.als0 + this.als1 > 0) {
                ratio = (int) ((float) (this.als0 * 1000) / (float) (this.als1 + this.als0));
            }

            if(ratio <  450) {
                ch_idx = 0;
            }else if(ratio < 640){
                ch_idx = 1;
            }else if(ratio < 850){
                ch_idx = 2;
            }

            this.lux = (double)((this.als0 * this.ch0_c[ch_idx])
                    - (this.als1 * this.ch1_c[ch_idx])) / 10000.0;

        }
    }

    private void setBits(int reg, int mask, int shift, int value) throws IOException {
        int currentValue = this.device.readRegByte(reg);
        currentValue &= ~mask;
        currentValue |= (value << shift);
        this.device.writeRegByte(reg, (byte) currentValue);
    }

    private int getBits(int reg, int mask, int shift) throws IOException {
        int currentValue = this.device.readRegByte(reg);
        return (currentValue & mask) >> shift;
    }
}
