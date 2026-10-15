package com.nikita23830.minecraftd3d.api;

public final class Matrix4 {
    public final float[] m = new float[16];

    public Matrix4() {
        identity();
    }

    public Matrix4 identity() {
        for (int i = 0; i < 16; i++) m[i] = (i % 5 == 0) ? 1 : 0;
        return this;
    }

    public Matrix4 set(Matrix4 o) {
        System.arraycopy(o.m, 0, m, 0, 16);
        return this;
    }

    public Matrix4 mul(Matrix4 b) {
        return mul(b.m);
    }

    public Matrix4 mul(float[] b) {
        float[] r = new float[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                r[i * 4 + j] = m[i * 4] * b[j] + m[i * 4 + 1] * b[4 + j] + m[i * 4 + 2] * b[8 + j] + m[i * 4 + 3] * b[12 + j];
        System.arraycopy(r, 0, m, 0, 16);
        return this;
    }

    public Matrix4 translate(float x, float y, float z) {
        return mul(new float[] { 1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1 });
    }

    public Matrix4 scale(float x, float y, float z) {
        return mul(new float[] { x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1 });
    }

    public Matrix4 rotate(float angle, float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len == 0) return this;
        x /= len; y /= len; z /= len;
        double a = Math.toRadians(angle);
        float c = (float) Math.cos(a), s = (float) Math.sin(a), t = 1 - c;
        return mul(new float[] {
            t * x * x + c, t * x * y - s * z, t * x * z + s * y, 0,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x, 0,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c, 0,
            0, 0, 0, 1,
        });
    }

    public void toColumnMajor(float[] out) {
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) out[j * 4 + i] = m[i * 4 + j];
    }
}
