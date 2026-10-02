package com.yjbrly.fivesolver;

import com.yjbrly.fivesolver.MainActivity.Point;

import java.util.ArrayList;
import java.util.List;

public class GameLogic {
    public static final int BLACK = 0, WHITE = 1;
    private static int boardSize = 15;

    public static void setBoardSize(int size) { boardSize = size; }

    public static boolean isOccupied(List<Point> moves, int x, int y) {
        for (Point p : moves) {
            if (p.x == x && p.y == y) return true;
        }
        return false;
    }

    public static boolean isGameFinished(List<Point> moves) {
        return isFiveInRow(moves) || moves.size() == boardSize * boardSize;
    }

    public static boolean isFiveInRow(List<Point> moves) {
        if (moves.isEmpty()) return false;
        int lastX = moves.get(moves.size() - 1).x;
        int lastY = moves.get(moves.size() - 1).y;
        int color = (moves.size() - 1) % 2;
        return checkWin(moves, lastX, lastY, color);
    }

    private static boolean checkWin(List<Point> moves, int x, int y, int color) {
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            int count = 1;
            for (int step = 1; step <= 5; step++) {
                int nx = x + d[0] * step, ny = y + d[1] * step;
                if (!isValid(nx, ny)) break;
                if (getColorAt(moves, nx, ny) == color) count++;
                else break;
            }
            for (int step = 1; step <= 5; step++) {
                int nx = x - d[0] * step, ny = y - d[1] * step;
                if (!isValid(nx, ny)) break;
                if (getColorAt(moves, nx, ny) == color) count++;
                else break;
            }
            if (count >= 5) return true;
        }
        return false;
    }

    private static int getColorAt(List<Point> moves, int x, int y) {
        for (int i = 0; i < moves.size(); i++) {
            Point p = moves.get(i);
            if (p.x == x && p.y == y) return i % 2;
        }
        return -1;
    }

    private static boolean isValid(int x, int y) {
        return x >= 0 && x < boardSize && y >= 0 && y < boardSize;
    }

    public static int[][] getWinningLine(List<Point> moves) {
        if (moves.isEmpty()) return null;
        int lastX = moves.get(moves.size() - 1).x;
        int lastY = moves.get(moves.size() - 1).y;
        int color = (moves.size() - 1) % 2;
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            ArrayList<int[]> forward = new ArrayList<int[]>();
            ArrayList<int[]> backward = new ArrayList<int[]>();
            for (int step = 1; step <= 5; step++) {
                int nx = lastX + d[0] * step, ny = lastY + d[1] * step;
                if (!isValid(nx, ny) || getColorAt(moves, nx, ny) != color) break;
                forward.add(new int[]{nx, ny});
            }
            for (int step = 1; step <= 5; step++) {
                int nx = lastX - d[0] * step, ny = lastY - d[1] * step;
                if (!isValid(nx, ny) || getColorAt(moves, nx, ny) != color) break;
                backward.add(new int[]{nx, ny});
            }
            if (forward.size() + backward.size() + 1 >= 5) {
                ArrayList<int[]> line = new ArrayList<int[]>();
                for (int j = backward.size() - 1; j >= 0; j--) line.add(backward.get(j));
                line.add(new int[]{lastX, lastY});
                for (int j = 0; j < forward.size(); j++) line.add(forward.get(j));
                return line.toArray(new int[line.size()][2]);
            }
        }
        return null;
    }
}
