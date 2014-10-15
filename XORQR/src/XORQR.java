import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Scanner;

import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.qrcode.decoder.Decoder;


// Class for solving the XORQR problem in the 2014 ASIS Cyber Security Contest
//
// Connects to the server specified in the problem and processes the series of
// encoded QR codes as they come. The program will not in normal cases exit on
// its own as it continually reads from the server, so make to close it after
// viewing the solution.
//
// Compilation note: You will need to include the ZXing core library in order
// to compile (it's used for the QR decoding). You can download a jar from
// Maven central here: http://mvnrepository.com/artifact/com.google.zxing/core
public class XORQR {
    public static void main(String[] args) throws IOException {
        // connect to problem server
        Socket nc = new Socket("asis-ctf.ir", 12431);

        Scanner in = new Scanner(nc.getInputStream());
        OutputStream out = nc.getOutputStream();

        // skip to instructions for starting the challenge
        scan(in, "send \"START\"");

        // start the challenge
        System.out.println("Sending: START");
        out.write("START\n".getBytes());

        // the challenge is a series of XOR'd QR codes, one after another
        while (true) {
            // parse the encoded QR matrix supplied by the problem
            boolean[][] matrix = parseMatrix(in);

            // if the matrix was null that means there was an error parsing it
            // and we should stop
            if (matrix == null) break;

            // generate the mask for this matrix
            boolean[] mask = getMaskRow(matrix);

            // apply the mask to get the actual QR code
            boolean[][] qr = new boolean[matrix.length][matrix.length];
            for (int y = 0; y < matrix.length; y++) {
                for (int x = 0; x < matrix.length; x++) {
                    qr[y][x] = mask[x] ^ matrix[y][x];
                }
            }

            // decode the QR into its textual message then send it
            try {
                String text = new Decoder().decode(qr).getText();
                System.out.println("Sending: " + text);
                out.write((text + "\n").getBytes());
            } catch (ChecksumException | FormatException e) {
                e.printStackTrace();
                break;
            }

            // wait for the OK that is sent after the server verifies our answer
            scan(in, "OK");
        }

        // keep printing socket data in case it holds some clues
        scan(in, null);
    }

    // scans the provided scanner until the given line is reached
    // note: this function will write each line read to standard output
    private static void scan(Scanner in, String line) {
        while (true) {
            while (!in.hasNextLine()) ;
            String cur = in.nextLine();
            System.out.println(cur);
            if (cur.equals(line)) break;
        }
    }

    // parses the encoded matrix from the standard in
    // note: returning null indicates
    private static boolean[][] parseMatrix(Scanner in) {
        String firstLine = in.nextLine();
        int n = firstLine.length();

        if (n < 21) { // 21 is minimum size of QR code
            // print out the first line in case there was useful data there
            // since after this method returns it'll be lost
            System.out.println(firstLine);
            return null;
        }

        // create the matrix by reading each line one after another and filling
        // the matrix with boolean values indicating whether that spot is
        // black or white
        boolean[][] matrix = new boolean[n][n];
        for (int lineNo = 0; lineNo < n; lineNo++) {
            String line = (lineNo > 0 ? in.nextLine() : firstLine);
            System.out.println(line);
            for (int i = 0; i < line.length(); i++) {
                matrix[lineNo][i] = (line.charAt(i) == '+'); // + is true, - is false
            }
        }

        return matrix;
    }

    // position boxes are the three boxes in the top left, top right, and
    // bottom left corners of the QR code and are always the same size
    // regardless of the size of the QR code
    public static final int POSITION_BOX_SIZE = 7;

    // our "magic row" is at the bottom of the top two position boxes because
    // the QR spec determines the value for each cell (it's does not vary
    // depending on the data)
    // since we always know what it is then we can generate the mask for the
    // rest of the QR code because it'll be the same as the row mask for this
    // row
    public static final int MAGIC_ROW_INDEX = POSITION_BOX_SIZE - 1;

    // generates the magic row for an n-sized QR code
    public static boolean[] getMagicRow(int n) {
        boolean[] result = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean value;
            if (i < POSITION_BOX_SIZE || i >= n - POSITION_BOX_SIZE) {
                value = true; // position box outlines are black
            } else {
                value = (i % 2 == 0); // alternate for the timing pattern
            }
            result[i] = value;
        }
        return result;
    }

    // calculates the mask row for the given matrix
    public static boolean[] getMaskRow(boolean[][] matrix) {
        boolean[] row = matrix[MAGIC_ROW_INDEX];
        int n = row.length;
        boolean[] magic = getMagicRow(n);
        boolean[] mask = new boolean[n];
        for (int i = 0; i < n; i++) {
            mask[i] = row[i] ^ magic[i];
        }
        return mask;
    }


    // note the following code is not part of the final solution but was useful
    // in determining the pattern
    private static Boolean[][] parseMask(Scanner in) {
        Boolean[][] mask = null;
        String line;
        int lineNo = 0;
        while (!(line = in.nextLine()).isEmpty()) {
            if (mask == null) mask = new Boolean[line.length()][line.length()];
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                Boolean val = (c == '+' ? Boolean.TRUE : (c == '-' ? Boolean.FALSE : null));
                mask[lineNo][i] = val;
            }
            lineNo++;
        }
        return mask;
    }

    private static void printMatrix(boolean[][] matrix) {
        for (boolean[] row : matrix) {
            for (boolean val : row) {
                System.out.print(val ? "██" : "  ");
            }
            System.out.println();
        }
    }

    private static void printMatrix(Boolean[][] matrix) {
        for (Boolean[] row : matrix) {
            for (Boolean val : row) {
                System.out.print((val == null ? "XX" : (val ? "██" : "  ")));
            }
            System.out.println();
        }
    }
}
