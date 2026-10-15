package com.nikita23830.minecraftd3d;

final class Matrices {
    private Matrices() {}

    static float[] reverseZ(float[] p) {
        float[] r = p.clone();
        for (int k = 0; k < 4; k++) r[8 + k] = 0.5f * p[12 + k] - 0.5f * p[8 + k];
        return r;
    }

    static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                float v = 0;
                for (int k = 0; k < 4; k++) v += a[i * 4 + k] * b[k * 4 + j];
                r[i * 4 + j] = v;
            }
        return r;
    }
}
