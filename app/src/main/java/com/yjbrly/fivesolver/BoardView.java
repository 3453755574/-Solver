package com.yjbrly.fivesolver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class BoardView extends View {
    private int boardSize = 15;
    private int boardBgColor = Color.parseColor("#A2A2A2");
    private int gridColor = Color.parseColor("#3A3A3A");
    private int blackStoneColor = Color.BLACK;
    private int whiteStoneColor = Color.WHITE;
    private float stoneSizeRatio = 0.4f;

    private Paint gridPaint, blackPaint, whitePaint, highlightPaint;
    private float cellSize;
    private float offsetX, offsetY;
    private List<MainActivity.Point> moveHistory;
    private int lastHighlighted = -1;
    private OnMoveListener moveListener;

    private Paint numberPaintBlack, numberPaintWhite;
    private float paddingBoard;

    private int[][] winLine = null;
    private Paint winLinePaint;

    private int hintX = -1, hintY = -1;
    private String hintScore = null;
    private Paint hintPaint;

    private int selectedX = -1, selectedY = -1;
    private Paint selectionPaint;
    private boolean showMoveNumbers = true;

    private Path clipPath;
    private RectF rectF;


    private List<CandidatePoint> candidates = new ArrayList<>();
    private boolean multiAnalysisMode = false;
    private boolean multiAnalysisComplete = false;
    private OnCandidateClickListener candidateListener;
    private Paint candidateBgPaint, candidateTextPaint, candidateBorderPaint;


    private static class LostPoint {
        int x, y;
        int stepCount;
        LostPoint(int x, int y, int stepCount) {
            this.x = x; this.y = y; this.stepCount = stepCount;
        }
    }
    private List<LostPoint> lostPoints = new ArrayList<>();
    private Paint lostPointPaint;
    private int currentMoveCount = 0;

    public interface OnMoveListener {
        void onMove(int x, int y);
    }

    public interface OnCandidateClickListener {
        void onCandidateClick(int rank, int x, int y);
    }

    public static class CandidatePoint {
        public int rank;
        public int x, y;
        public int eval;
        public double winrate;
        public String depth;

        public CandidatePoint(int x, int y, int eval, double winrate, String depth) {
            this.x = x;
            this.y = y;
            this.eval = eval;
            this.winrate = winrate;
            this.depth = depth;
        }
    }

    public BoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);

        blackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blackPaint.setStyle(Paint.Style.FILL);

        whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        whitePaint.setStyle(Paint.Style.FILL);
        whitePaint.setShadowLayer(2, 1, 1, Color.GRAY);

        highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightPaint.setColor(Color.parseColor("#FFD700"));
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(3f);

        numberPaintBlack = new Paint(Paint.ANTI_ALIAS_FLAG);
        numberPaintBlack.setTextAlign(Paint.Align.CENTER);
        numberPaintBlack.setFakeBoldText(true);

        numberPaintWhite = new Paint(Paint.ANTI_ALIAS_FLAG);
        numberPaintWhite.setTextAlign(Paint.Align.CENTER);
        numberPaintWhite.setFakeBoldText(true);

        winLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        winLinePaint.setColor(Color.parseColor("#FFD700"));
        winLinePaint.setStrokeWidth(6f);
        winLinePaint.setStyle(Paint.Style.STROKE);

        hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hintPaint.setColor(Color.argb(180, 255, 0, 0));
        hintPaint.setStyle(Paint.Style.FILL);

        selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionPaint.setColor(Color.RED);
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(3f);

        candidateBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        candidateBgPaint.setStyle(Paint.Style.FILL);
        candidateBgPaint.setAlpha(200);

        candidateTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        candidateTextPaint.setTextAlign(Paint.Align.CENTER);
        candidateTextPaint.setFakeBoldText(true);
        candidateTextPaint.setColor(Color.WHITE);

        candidateBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        candidateBorderPaint.setStyle(Paint.Style.STROKE);
        candidateBorderPaint.setStrokeWidth(2f);
        candidateBorderPaint.setColor(Color.WHITE);

        lostPointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lostPointPaint.setColor(Color.WHITE);
        lostPointPaint.setStyle(Paint.Style.FILL);
        lostPointPaint.setAlpha(180);

        clipPath = new Path();
        rectF = new RectF();
    }


    private int getSmoothColor(float t) {
        float[] positions = {
            0.00f, 0.15f, 0.30f, 0.45f, 0.60f, 0.75f, 0.88f, 1.00f
        };
        int[][] colors = {
            {0, 0, 128},
            {0, 0, 255},
            {0, 180, 255},
            {0, 255, 200},
            {0, 255, 0},
            {255, 255, 0},
            {255, 128, 0},
            {139, 69, 19}
        };
        if (t <= positions[0]) return Color.rgb(colors[0][0], colors[0][1], colors[0][2]);
        if (t >= positions[positions.length-1])
            return Color.rgb(colors[colors.length-1][0], colors[colors.length-1][1], colors[colors.length-1][2]);
        int i = 0;
        for (; i < positions.length - 1; i++) {
            if (t <= positions[i + 1]) break;
        }
        float localT = (t - positions[i]) / (positions[i + 1] - positions[i]);
        int r = (int)(colors[i][0] + (colors[i+1][0] - colors[i][0]) * localT);
        int g = (int)(colors[i][1] + (colors[i+1][1] - colors[i][1]) * localT);
        int b = (int)(colors[i][2] + (colors[i+1][2] - colors[i][2]) * localT);
        return Color.rgb(r, g, b);
    }

    public void setStoneSizeRatio(float ratio) {
        this.stoneSizeRatio = Math.max(0.3f, Math.min(0.5f, ratio));
        invalidate();
    }

    public void setBoardSize(int size) {
        if (size >= 6 && size <= 20) {
            this.boardSize = size;
            requestLayout();
            invalidate();
        }
    }

    public void setShowMoveNumbers(boolean show) { this.showMoveNumbers = show; invalidate(); }

    public void setMoveHistory(List<MainActivity.Point> history, int lastMoveIndex) {
        this.moveHistory = history;
        this.lastHighlighted = lastMoveIndex;
        int newMoveCount = (history == null) ? 0 : history.size();
        if (newMoveCount != this.currentMoveCount) {
            this.currentMoveCount = newMoveCount;
            List<LostPoint> toKeep = new ArrayList<>();
            for (LostPoint lp : lostPoints) {
                if (lp.stepCount == currentMoveCount) {
                    toKeep.add(lp);
                }
            }
            if (toKeep.size() != lostPoints.size()) {
                lostPoints.clear();
                lostPoints.addAll(toKeep);
            }
        } else {
            this.currentMoveCount = newMoveCount;
        }
        invalidate();
    }

    public void setOnMoveListener(OnMoveListener listener) { this.moveListener = listener; }

    public void setOnCandidateClickListener(OnCandidateClickListener listener) { this.candidateListener = listener; }

    public void setWinLine(int[][] line) { this.winLine = line; invalidate(); }

    public void setBestHint(int x, int y, String score) {
        this.hintX = x;
        this.hintY = y;
        this.hintScore = score;
        invalidate();
    }

    public void clearHint() {
        hintX = -1;
        hintY = -1;
        hintScore = null;
        invalidate();
    }

    public void clearSelection() {
        selectedX = -1;
        selectedY = -1;
        invalidate();
    }

    public void addLostPoint(int x, int y) {
        if (multiAnalysisMode) return;
        if (x < 0 || x >= boardSize || y < 0 || y >= boardSize) return;
        for (LostPoint lp : lostPoints) {
            if (lp.x == x && lp.y == y) return;
        }
        lostPoints.add(new LostPoint(x, y, currentMoveCount));
        invalidate();
    }

    public void clearLostPoints() {
        if (!lostPoints.isEmpty()) {
            lostPoints.clear();
            invalidate();
        }
    }

    public void setMultiAnalysisMode(boolean enabled) {
        this.multiAnalysisMode = enabled;
        if (!enabled) {
            this.candidates.clear();
            this.multiAnalysisComplete = false;
        } else {
            this.multiAnalysisComplete = false;
            clearLostPoints();
        }
        invalidate();
    }

    public boolean isMultiAnalysisMode() { return multiAnalysisMode; }

    public void setMultiAnalysisComplete(boolean complete) {
        this.multiAnalysisComplete = complete;
        invalidate();
    }

    public boolean isMultiAnalysisComplete() { return multiAnalysisComplete; }

    /**
     * 更新候选点：添加或更新现有候选点，然后按胜率排序并重编号，最后刷新界面
     */
    public void updateCandidate(CandidatePoint cp) {
        if (!multiAnalysisMode) return;
        boolean found = false;
        for (int i = 0; i < candidates.size(); i++) {
            CandidatePoint existing = candidates.get(i);
            if (existing.x == cp.x && existing.y == cp.y) {

                existing.eval = cp.eval;
                existing.winrate = cp.winrate;
                existing.depth = cp.depth;
                found = true;
                break;
            }
        }
        if (!found) {
            candidates.add(cp);
        }

        sortAndRenumber();

        invalidate();
    }

    /**
     * 按胜率降序排序，胜率相同则按评估值降序
     */
    private void sortAndRenumber() {
        if (candidates.isEmpty()) return;
        Collections.sort(candidates, new Comparator<CandidatePoint>() {
                @Override
                public int compare(CandidatePoint a, CandidatePoint b) {

                    if (a.winrate != b.winrate) {
                        return Double.compare(b.winrate, a.winrate);
                    }

                    return Integer.compare(b.eval, a.eval);
                }
            });

        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).rank = i + 1;
        }
    }

    public void clearCandidates() {
        candidates.clear();
        multiAnalysisMode = false;
        multiAnalysisComplete = false;
        invalidate();
    }

    public List<CandidatePoint> getCandidates() { return candidates; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int maxSize = (int)(screenWidth * 0.95f);
        int minSize = (int)(getResources().getDisplayMetrics().density * 300);
        int desiredSize = Math.max(minSize, Math.min(maxSize, screenWidth - 16));
        setMeasuredDimension(desiredSize, desiredSize);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int actualSize = w;
        float paddingRatio = boardSize <= 10 ? 0.08f : 0.04f;
        paddingBoard = actualSize * paddingRatio;
        float availSize = actualSize - 2 * paddingBoard;
        cellSize = availSize / (boardSize - 1);
        offsetX = paddingBoard;
        offsetY = paddingBoard;

        numberPaintBlack.setTextSize(cellSize * 0.45f);
        numberPaintWhite.setTextSize(cellSize * 0.45f);
        candidateTextPaint.setTextSize(cellSize * 0.4f);

        float radius = 24 * getResources().getDisplayMetrics().density;
        rectF.set(0, 0, w, h);
        clipPath.reset();
        clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawColor(boardBgColor);

        gridPaint.setColor(gridColor);
        for (int i = 0; i < boardSize; i++) {
            float x = offsetX + i * cellSize;
            float y = offsetY + i * cellSize;
            canvas.drawLine(offsetX, y, offsetX + (boardSize - 1) * cellSize, y, gridPaint);
            canvas.drawLine(x, offsetY, x, offsetY + (boardSize - 1) * cellSize, gridPaint);
        }

        drawStarPoints(canvas);


        if (multiAnalysisMode && candidates != null && !candidates.isEmpty()) {
            float radius = cellSize * 0.35f;
            int total = candidates.size();
            for (CandidatePoint cp : candidates) {
                boolean occupied = false;
                if (moveHistory != null) {
                    for (MainActivity.Point p : moveHistory) {
                        if (p.x == cp.x && p.y == cp.y) { occupied = true; break; }
                    }
                }
                if (occupied) continue;

                float cx = offsetX + cp.x * cellSize;
                float cy = offsetY + cp.y * cellSize;

                float t = (total <= 1) ? 0 : (float)(cp.rank - 1) / (total - 1);
                int color = getSmoothColor(t);
                candidateBgPaint.setColor(color);
                canvas.drawCircle(cx, cy, radius, candidateBgPaint);
                canvas.drawCircle(cx, cy, radius, candidateBorderPaint);


                if (cp.rank <= 5) {
                    String numStr = String.valueOf(cp.rank);
                    Paint.FontMetrics fm = candidateTextPaint.getFontMetrics();
                    float baseline = cy - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText(numStr, cx, baseline, candidateTextPaint);
                }
            }
        }


        if (moveHistory != null) {
            for (int i = 0; i < moveHistory.size(); i++) {
                MainActivity.Point p = moveHistory.get(i);
                float cx = offsetX + p.x * cellSize;
                float cy = offsetY + p.y * cellSize;
                boolean isBlack = (i % 2 == 0);
                Paint stonePaint = isBlack ? blackPaint : whitePaint;
                stonePaint.setColor(isBlack ? blackStoneColor : whiteStoneColor);
                canvas.drawCircle(cx, cy, cellSize * stoneSizeRatio, stonePaint);

                if (showMoveNumbers) {
                    int stepNum = i + 1;
                    String numStr = String.valueOf(stepNum);
                    Paint numPaint = isBlack ? numberPaintWhite : numberPaintBlack;
                    numPaint.setColor(isBlack ? Color.WHITE : Color.BLACK);
                    Paint.FontMetrics fm = numPaint.getFontMetrics();
                    float baseline = cy - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText(numStr, cx, baseline, numPaint);
                }
            }
        }


        if (!multiAnalysisMode && lostPoints != null && !lostPoints.isEmpty()) {
            float radius = cellSize * 0.3f;
            for (LostPoint lp : lostPoints) {
                if (lp.stepCount != currentMoveCount) continue;
                float cx = offsetX + lp.x * cellSize;
                float cy = offsetY + lp.y * cellSize;
                canvas.drawCircle(cx, cy, radius, lostPointPaint);
            }
        }

        if (hintX >= 0 && hintY >= 0) {
            float cx = offsetX + hintX * cellSize;
            float cy = offsetY + hintY * cellSize;
            canvas.drawCircle(cx, cy, cellSize * 0.22f, hintPaint);
        }

        if (lastHighlighted >= 0 && moveHistory != null && lastHighlighted < moveHistory.size() && winLine == null) {
            MainActivity.Point p = moveHistory.get(lastHighlighted);
            canvas.drawCircle(offsetX + p.x * cellSize, offsetY + p.y * cellSize,
                              cellSize * (stoneSizeRatio + 0.02f), highlightPaint);
        }

        if (selectedX >= 0 && selectedY >= 0) {
            float cx = offsetX + selectedX * cellSize;
            float cy = offsetY + selectedY * cellSize;
            float half = cellSize * stoneSizeRatio + 6f;
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, selectionPaint);
        }

        if (winLine != null && winLine.length >= 2) {
            for (int i = 0; i < winLine.length - 1; i++) {
                float x1 = offsetX + winLine[i][0] * cellSize;
                float y1 = offsetY + winLine[i][1] * cellSize;
                float x2 = offsetX + winLine[i+1][0] * cellSize;
                float y2 = offsetY + winLine[i+1][1] * cellSize;
                canvas.drawLine(x1, y1, x2, y2, winLinePaint);
            }
        }

        canvas.restore();
    }

    private void drawStarPoints(Canvas canvas) {
        Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        starPaint.setColor(gridColor);
        float radius = cellSize * 0.1f;

        int d = (boardSize >= 13) ? 3 : 2;
        ArrayList<int[]> stars = new ArrayList<>();
        stars.add(new int[]{d, d});
        stars.add(new int[]{d, boardSize - 1 - d});
        stars.add(new int[]{boardSize - 1 - d, d});
        stars.add(new int[]{boardSize - 1 - d, boardSize - 1 - d});

        if (boardSize % 2 == 1) {
            int mid = boardSize / 2;
            stars.add(new int[]{mid, mid});
            if (boardSize >= 13) {
                stars.add(new int[]{mid, d});
                stars.add(new int[]{mid, boardSize - 1 - d});
                stars.add(new int[]{d, mid});
                stars.add(new int[]{boardSize - 1 - d, mid});
            }
        }
        for (int[] s : stars) {
            canvas.drawCircle(offsetX + s[0] * cellSize, offsetY + s[1] * cellSize, radius, starPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (multiAnalysisMode && !multiAnalysisComplete) {
                return true;
            }

            float x = event.getX() - offsetX;
            float y = event.getY() - offsetY;
            int col = Math.round(x / cellSize);
            int row = Math.round(y / cellSize);
            if (col >= 0 && col < boardSize && row >= 0 && row < boardSize) {

                if (multiAnalysisMode && multiAnalysisComplete && candidates != null) {
                    for (CandidatePoint cp : candidates) {
                        if (cp.x == col && cp.y == row) {
                            if (candidateListener != null) {
                                candidateListener.onCandidateClick(cp.rank, cp.x, cp.y);
                            }
                            return true;
                        }
                    }
                    return true;
                }

                boolean occupied = false;
                if (moveHistory != null) {
                    for (MainActivity.Point p : moveHistory) {
                        if (p.x == col && p.y == row) { occupied = true; break; }
                    }
                }
                if (occupied) {
                    selectedX = -1;
                    selectedY = -1;
                    invalidate();
                    return true;
                }
                if (selectedX == -1) {
                    selectedX = col;
                    selectedY = row;
                    invalidate();
                } else if (selectedX == col && selectedY == row) {
                    if (moveListener != null) {
                        moveListener.onMove(col, row);
                    }
                    selectedX = -1;
                    selectedY = -1;
                    invalidate();
                } else {
                    selectedX = col;
                    selectedY = row;
                    invalidate();
                }
            }
        }
        return true;
    }
}
