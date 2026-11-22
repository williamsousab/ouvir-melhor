package br.com.ouvirmelhor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.View;

/**
 * Audiograma simplificado: ouvido direito = O vermelho, esquerdo = X azul
 * (convenção usada pelos fonoaudiólogos). Mais para baixo = escuta pior.
 */
final class AudiogramView extends View {

    private static final float MAX_DB = 60f;
    private static final int RED = Color.parseColor("#FF5A5A");
    private static final int BLUE = Color.parseColor("#4DA3FF");

    private final float[] left = new float[6];
    private final float[] right = new float[6];
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineL = new Paint(Paint.ANTI_ALIAS_FLAG);

    AudiogramView(Context c) {
        super(c);
        grid.setColor(Color.parseColor("#33414D"));
        grid.setStrokeWidth(dp(1));
        label.setColor(Color.parseColor("#A9B4BE"));
        label.setTextSize(dp(12));
        label.setTextAlign(Paint.Align.CENTER);
        lineR.setColor(RED);
        lineR.setStrokeWidth(dp(2.5f));
        lineR.setStyle(Paint.Style.STROKE);
        lineL.setColor(BLUE);
        lineL.setStrokeWidth(dp(2.5f));
        lineL.setStyle(Paint.Style.STROKE);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    void setData(float[] l, float[] r) {
        System.arraycopy(l, 0, left, 0, 6);
        System.arraycopy(r, 0, right, 0, 6);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) dp(230));
    }

    @Override
    protected void onDraw(Canvas c) {
        float padL = dp(16), padR = dp(16), padT = dp(12), padB = dp(28);
        float w = getWidth() - padL - padR;
        float h = getHeight() - padT - padB;

        for (int db = 0; db <= (int) MAX_DB; db += 15) {
            float y = padT + h * db / MAX_DB;
            c.drawLine(padL, y, padL + w, y, grid);
        }
        float[] xs = new float[6];
        for (int i = 0; i < 6; i++) {
            xs[i] = padL + w * (i + 0.5f) / 6f;
            c.drawLine(xs[i], padT, xs[i], padT + h, grid);
            c.drawText(Params.BAND_LABELS[i], xs[i], getHeight() - dp(8), label);
        }

        float m = dp(6);
        float prevX = 0, prevY = 0;
        for (int i = 0; i < 6; i++) {
            float y = padT + h * Math.min(MAX_DB, right[i]) / MAX_DB;
            if (i > 0) c.drawLine(prevX, prevY, xs[i], y, lineR);
            c.drawCircle(xs[i], y, m, lineR);
            prevX = xs[i];
            prevY = y;
        }
        for (int i = 0; i < 6; i++) {
            float y = padT + h * Math.min(MAX_DB, left[i]) / MAX_DB;
            if (i > 0) c.drawLine(prevX, prevY, xs[i], y, lineL);
            c.drawLine(xs[i] - m, y - m, xs[i] + m, y + m, lineL);
            c.drawLine(xs[i] - m, y + m, xs[i] + m, y - m, lineL);
            prevX = xs[i];
            prevY = y;
        }
    }
}
