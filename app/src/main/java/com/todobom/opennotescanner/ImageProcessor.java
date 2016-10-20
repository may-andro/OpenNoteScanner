package com.todobom.opennotescanner;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.shapes.PathShape;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

import com.todobom.opennotescanner.helpers.OpenNoteMessage;
import com.todobom.opennotescanner.helpers.PreviewFrame;
import com.todobom.opennotescanner.helpers.Quadrilateral;
import com.todobom.opennotescanner.helpers.ScannedDocument;
import com.todobom.opennotescanner.views.HUDCanvasView;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

/**
 * Created by allgood on 05/03/16.
 */
public class ImageProcessor extends Handler {

    private static final String TAG = "ImageProcessor";
    private final Handler mUiHandler;
    private final OpenNoteScannerActivity mMainActivity;
    private boolean mBugRotate;
    private boolean colorMode=true;
    private boolean filterMode=false;
    private double colorGain = 1.5;       // contrast
    private double colorBias = 0;         // bright
    private int colorThresh = 300;        // threshold
    private Size mPreviewSize;
    private Point[] mPreviewPoints;


    public ImageProcessor ( Looper looper , Handler uiHandler , OpenNoteScannerActivity mainActivity ) {
        super(looper);
        mUiHandler = uiHandler;
        mMainActivity = mainActivity;

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(mainActivity);
        mBugRotate = sharedPref.getBoolean("bug_rotate",false);
    }

    public void handleMessage ( Message msg ) {

        if (msg.obj.getClass() == OpenNoteMessage.class) {

            OpenNoteMessage obj = (OpenNoteMessage) msg.obj;

            String command = obj.getCommand();

            Log.d(TAG, "Message Received: " + command + " - " + obj.getObj().toString() );

            if ( command.equals("previewFrame")) {
                processPreviewFrame((PreviewFrame) obj.getObj());
            } else if ( command.equals("pictureTaken")) {
                processPicture((Mat) obj.getObj());
            } else if ( command.equals("colorMode")) {
                colorMode=(Boolean) obj.getObj();
            } else if ( command.equals("filterMode")) {
                filterMode=(Boolean) obj.getObj();
            }
        }
    }

    int count = 2;
    int countErrorDisplay = 2;

    private void processPreviewFrame( PreviewFrame previewFrame ) {

        final Mat frame = previewFrame.getFrame();

        final boolean autoMode = previewFrame.isAutoMode();
        final boolean previewOnly = previewFrame.isPreviewOnly();

        System.out.println("ImageProcessor.processPreviewFrame detectPreviewDocument(frame)="+detectPreviewDocument(frame));
        System.out.println("ImageProcessor.processPreviewFrame autoMode="+autoMode);
        System.out.println("ImageProcessor.processPreviewFrame previewOnly="+previewOnly);

        if ( detectPreviewDocument(frame) && (autoMode && previewOnly )  ) {

            System.out.println("ImageProcessor.processPreviewFrame inside");

            if(count>0){
                count--;
                mMainActivity.showToast("Document found...");
            }
            else{
                mMainActivity.showToast("Don't Move.Hold it right there");

                mMainActivity.waitSpinnerVisible();

                mMainActivity.requestPicture();

                count = 2;
            }


        }else if ( detectPreviewDocument(frame) && (!autoMode)  ) {
            if(count>0){
                count--;
                mMainActivity.showToast("Document found...");
            }
            else{
                count = 2;
            }


        }else{
            mMainActivity.displayMessage("");

            System.out.println("ImageProcessor.processPreviewFrame else");
        }

        frame.release();
        mMainActivity.setImageProcessorBusy(false);

    }

    public void processPicture( Mat picture ) {

        Mat img = Imgcodecs.imdecode(picture, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
        picture.release();

        Log.d(TAG, "processPicture - imported image " + img.size().width + "x" + img.size().height);

        if (mBugRotate) {
            Core.flip(img, img, 1 );
            Core.flip(img, img, 0 );
        }

        ScannedDocument doc = detectDocument(img);
        mMainActivity.saveDocument(doc);

        doc.release();
        picture.release();

        mMainActivity.setImageProcessorBusy(false);
        mMainActivity.waitSpinnerInvisible();
    }

    private ScannedDocument detectDocument(Mat inputRgba) {
        ArrayList<MatOfPoint> contours = findContours(inputRgba);

        ScannedDocument sd = new ScannedDocument(inputRgba);

        Quadrilateral quad = getQuadrilateral(contours, inputRgba.size());

        Mat doc;

        if (quad != null) {
            sd.quadrilateral = quad;
            sd.previewPoints = mPreviewPoints;
            sd.previewSize = mPreviewSize;

            doc = fourPointTransform(inputRgba, quad.points);

        } else {
            doc = new Mat( inputRgba.size() , CvType.CV_8UC4 );
            inputRgba.copyTo(doc);
        }

        enhanceDocument(doc);
        return sd.setProcessed(doc);
    }

    int contourCount = 3;
    private boolean detectPreviewDocument(Mat inputRgba) {

        System.out.println("1ImageProcessor.detectPreviewDocument");

        ArrayList<MatOfPoint> contours = findContours(inputRgba);

        if(contours.size()==0){
            if(contourCount == 0){
                mMainActivity.showToast("No document found");
                contourCount = 50;
            }
            contourCount = contourCount - 1;
        }else{
            if(contourCount == 25){
                mMainActivity.showToast("Choose a contrast background.");
            }
            if(contourCount==0){
                contourCount=50;
            }
            contourCount = contourCount - 1;
        }

        Quadrilateral quad = getQuadrilateral(contours, inputRgba.size());

        System.out.println("ImageProcessor.detectPreviewDocument quad="+quad);


        mPreviewPoints = null;
        mPreviewSize = inputRgba.size();

        if (quad != null) {

            Point[] rescaledPoints = new Point[4];

            double ratio = inputRgba.size().height / 500;

            for ( int i=0; i<4 ; i++ ) {
                int x = Double.valueOf(quad.points[i].x*ratio).intValue();
                int y = Double.valueOf(quad.points[i].y*ratio).intValue();
                if (mBugRotate) {
                    rescaledPoints[(i+2)%4] = new Point( Math.abs(x- mPreviewSize.width), Math.abs(y- mPreviewSize.height));
                } else {
                    rescaledPoints[i] = new Point(x, y);
                }
            }

            mPreviewPoints = rescaledPoints;

            drawDocumentBox(mPreviewPoints, mPreviewSize);

            Log.d(TAG, quad.points[0].toString() + " , " + quad.points[1].toString() + " , " + quad.points[2].toString() + " , " + quad.points[3].toString());

            return true;
        }

        mMainActivity.getHUD().clear();
        mMainActivity.invalidateHUD();

        return false;

    }

    private void drawDocumentBox(Point[] points, Size stdSize) {

        System.out.println("2ImageProcessor.detectPreviewDocument");

        Path path = new Path();

        HUDCanvasView hud = mMainActivity.getHUD();

        // ATTENTION: axis are swapped

        float previewWidth = (float) stdSize.height;
        float previewHeight = (float) stdSize.width;

        path.moveTo( previewWidth - (float) points[0].y, (float) points[0].x );
        path.lineTo( previewWidth - (float) points[1].y, (float) points[1].x );
        path.lineTo( previewWidth - (float) points[2].y, (float) points[2].x );
        path.lineTo( previewWidth - (float) points[3].y, (float) points[3].x );
        path.close();

        PathShape newBox = new PathShape(path , previewWidth , previewHeight);

        Paint paint = new Paint();
        paint.setColor(Color.argb(64, 0, 255, 0));

        Paint border = new Paint();
        border.setColor(Color.rgb(0, 255, 0));
        border.setStrokeWidth(5);

        hud.clear();
        hud.addShape(newBox, paint, border);
        mMainActivity.invalidateHUD();
    }

    private Quadrilateral getQuadrilateral( ArrayList<MatOfPoint> contours , Size srcSize ) {
        System.out.println("ImageProcessor.getQuadrilateral");
        double ratio = srcSize.height / 500;
        int height = Double.valueOf(srcSize.height / ratio).intValue();
        int width = Double.valueOf(srcSize.width / ratio).intValue();
        Size size = new Size(width,height);

        System.out.println("ImageProcessor.getQuadrilateral size="+size);
        for ( MatOfPoint c: contours ) {
            MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
            double peri = Imgproc.arcLength(c2f, true);
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);

            Point[] points = approx.toArray();

            System.out.println("ImageProcessor.getQuadrilateral points.length="+points.length);
            // select biggest 4 angles polygon
            if (points.length == 4) {
                Point[] foundPoints = sortPoints(points);

                if (insideArea(foundPoints, size)) {
                    return new Quadrilateral( c , foundPoints );
                }
            }
        }

        return null;
    }

    private Point[] sortPoints( Point[] src ) {

        ArrayList<Point> srcPoints = new ArrayList<>(Arrays.asList(src));

        Point[] result = { null , null , null , null };

        Comparator<Point> sumComparator = new Comparator<Point>() {
            @Override
            public int compare(Point lhs, Point rhs) {
                return Double.valueOf(lhs.y + lhs.x).compareTo(rhs.y + rhs.x);
            }
        };

        Comparator<Point> diffComparator = new Comparator<Point>() {

            @Override
            public int compare(Point lhs, Point rhs) {
                return Double.valueOf(lhs.y - lhs.x).compareTo(rhs.y - rhs.x);
            }
        };

        // top-left corner = minimal sum
        result[0] = Collections.min(srcPoints, sumComparator);

        // bottom-right corner = maximal sum
        result[2] = Collections.max(srcPoints, sumComparator);

        // top-right corner = minimal diference
        result[1] = Collections.min(srcPoints, diffComparator);

        // bottom-left corner = maximal diference
        result[3] = Collections.max(srcPoints, diffComparator);

        return result;
    }

    private boolean insideArea(Point[] rp, Size size) {

        int width = Double.valueOf(size.width).intValue();
        int height = Double.valueOf(size.height).intValue();
        int baseMeasure = height/4;

        int bottomPos = height-baseMeasure;
        int topPos = baseMeasure;
        int leftPos = width/2-baseMeasure;
        int rightPos = width/2+baseMeasure;

        return (
                rp[0].x <= leftPos && rp[0].y <= topPos
                        && rp[1].x >= rightPos && rp[1].y <= topPos
                        && rp[2].x >= rightPos && rp[2].y >= bottomPos
                        && rp[3].x <= leftPos && rp[3].y >= bottomPos

        );
    }

    private void enhanceDocument( Mat src ) {
        if (colorMode && filterMode) {
            src.convertTo(src,-1, colorGain , colorBias);
            Mat mask = new Mat(src.size(), CvType.CV_8UC1);
            Imgproc.cvtColor(src,mask,Imgproc.COLOR_RGBA2GRAY);

            Mat copy = new Mat(src.size(), CvType.CV_8UC3);
            src.copyTo(copy);

            Imgproc.adaptiveThreshold(mask,mask,255,Imgproc.ADAPTIVE_THRESH_MEAN_C,Imgproc.THRESH_BINARY_INV,15,15);

            src.setTo(new Scalar(255,255,255));
            copy.copyTo(src,mask);

            copy.release();
            mask.release();

            // special color threshold algorithm
            colorThresh(src,colorThresh);
        } else if (!colorMode) {
            Imgproc.cvtColor(src,src,Imgproc.COLOR_RGBA2GRAY);
            if (filterMode) {
                Imgproc.adaptiveThreshold(src, src, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 15, 15);
            }
        }
    }

    /**
     * When a pixel have any of its three elements above the threshold
     * value and the average of the three values are less than 80% of the
     * higher one, brings all three values to the max possible keeping
     * the relation between them, any absolute white keeps the value, all
     * others go to absolute black.
     *
     * src must be a 3 channel image with 8 bits per channel
     *
     * @param src
     * @param threshold
     */
    private void colorThresh(Mat src, int threshold) {
        Size srcSize = src.size();
        int size = (int) (srcSize.height * srcSize.width)*3;
        byte[] d = new byte[size];
        src.get(0,0,d);

        for (int i=0; i < size; i+=3) {

            // the "& 0xff" operations are needed to convert the signed byte to double

            // avoid unneeded work
            if ( (double) (d[i] & 0xff) == 255 ) {
                continue;
            }

            double max = Math.max(Math.max((double) (d[i] & 0xff), (double) (d[i + 1] & 0xff)),
                    (double) (d[i + 2] & 0xff));
            double mean = ((double) (d[i] & 0xff) + (double) (d[i + 1] & 0xff)
                    + (double) (d[i + 2] & 0xff)) / 3;

            if (max > threshold && mean < max * 0.8) {
                d[i] = (byte) ((double) (d[i] & 0xff) * 255 / max);
                d[i + 1] = (byte) ((double) (d[i + 1] & 0xff) * 255 / max);
                d[i + 2] = (byte) ((double) (d[i + 2] & 0xff) * 255 / max);
            } else {
                d[i] = d[i + 1] = d[i + 2] = 0;
            }
        }
        src.put(0,0,d);
    }

    private Mat fourPointTransform( Mat src , Point[] pts ) {
        double ratio = src.size().height / 500;

        Point tl = pts[0];
        Point tr = pts[1];
        Point br = pts[2];
        Point bl = pts[3];

        double widthA = Math.sqrt(Math.pow(br.x - bl.x, 2) + Math.pow(br.y - bl.y, 2));
        double widthB = Math.sqrt(Math.pow(tr.x - tl.x, 2) + Math.pow(tr.y - tl.y, 2));

        double dw = Math.max(widthA, widthB)*ratio;
        int maxWidth = Double.valueOf(dw).intValue();

        double heightA = Math.sqrt(Math.pow(tr.x - br.x, 2) + Math.pow(tr.y - br.y, 2));
        double heightB = Math.sqrt(Math.pow(tl.x - bl.x, 2) + Math.pow(tl.y - bl.y, 2));

        double dh = Math.max(heightA, heightB)*ratio;
        int maxHeight = Double.valueOf(dh).intValue();

        Mat doc = new Mat(maxHeight, maxWidth, CvType.CV_8UC4);

        Mat src_mat = new Mat(4, 1, CvType.CV_32FC2);
        Mat dst_mat = new Mat(4, 1, CvType.CV_32FC2);

        src_mat.put(0, 0, tl.x*ratio, tl.y*ratio, tr.x*ratio, tr.y*ratio, br.x*ratio, br.y*ratio, bl.x*ratio, bl.y*ratio);
        dst_mat.put(0, 0, 0.0, 0.0, dw, 0.0, dw, dh, 0.0, dh);

        Mat m = Imgproc.getPerspectiveTransform(src_mat, dst_mat);

        Imgproc.warpPerspective(src, doc, m, doc.size());

        return doc;
    }

    private ArrayList<MatOfPoint> findContours(Mat src) {

        Mat grayImage = null;
        Mat cannedImage = null;
        Mat resizedImage = null;

        double ratio = src.size().height / 500;
        int height = Double.valueOf(src.size().height / ratio).intValue();
        int width = Double.valueOf(src.size().width / ratio).intValue();
        Size size = new Size(width,height);

        resizedImage = new Mat(size, CvType.CV_8UC4);
        grayImage = new Mat(size, CvType.CV_8UC4);
        cannedImage = new Mat(size, CvType.CV_8UC1);

        Imgproc.resize(src,resizedImage,size);
        Imgproc.cvtColor(resizedImage, grayImage, Imgproc.COLOR_RGBA2GRAY, 4);
        Imgproc.GaussianBlur(grayImage, grayImage, new Size(5, 5), 0);
        Imgproc.Canny(grayImage, cannedImage, 75, 200);

        ArrayList<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        Mat hierarchy = new Mat();

        Imgproc.findContours(cannedImage, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        hierarchy.release();

        Collections.sort(contours, new Comparator<MatOfPoint>() {

            @Override
            public int compare(MatOfPoint lhs, MatOfPoint rhs) {
                return Double.valueOf(Imgproc.contourArea(rhs)).compareTo(Imgproc.contourArea(lhs));
            }
        });

        resizedImage.release();
        grayImage.release();
        cannedImage.release();

        return contours;
    }


    public void setBugRotate(boolean bugRotate) {
        mBugRotate = bugRotate;
    }
}
