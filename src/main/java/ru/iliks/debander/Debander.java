package ru.iliks.debander;

import mil.nga.tiff.*;
import mil.nga.tiff.util.TiffConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

class BandingInfo {
    public final double r_avg;
    public final double g_avg;
    public final double b_avg;
    public final double[] r_x_mults;
    public final double[] g_x_mults;
    public final double[] b_x_mults;
    public final double[] r_y_mults;
    public final double[] g_y_mults;
    public final double[] b_y_mults;
    public final double[][] r_mults;
    public final double[][] g_mults;
    public final double[][] b_mults;

    public BandingInfo(
            double r_avg, double g_avg, double b_avg,
            double[] r_x_mults, double[] g_x_mults, double[] b_x_mults,
            double[] r_y_mults, double[] g_y_mults, double[] b_y_mults,
            double[][] r_mults, double[][] g_mults, double[][] b_mults) {
        this.r_avg = r_avg;
        this.g_avg = g_avg;
        this.b_avg = b_avg;
        this.r_x_mults = r_x_mults;
        this.g_x_mults = g_x_mults;
        this.b_x_mults = b_x_mults;
        this.r_y_mults = r_y_mults;
        this.g_y_mults = g_y_mults;
        this.b_y_mults = b_y_mults;
        this.r_mults = r_mults;
        this.g_mults = g_mults;
        this.b_mults = b_mults;
    }
}

public class Debander {
    public static void main(String[] args) throws IOException {
        final var debander = new Debander();
        var lightFieldFilename = "/Volumes/share-raid/Photo/film/2019/1908-1/mine/xe/debander/2022-01-02-0001-light-gblur20.tif";
//        final var lightFieldFilename = "/Volumes/share-raid/Photo/film/2019/1908-2/mine/xe/2021-12-28-lightsource-no-compression.tif";
        final var bandingInfo = debander.generateBandingInfo(lightFieldFilename);
        System.out.printf("Avg level: %f/%f/%f\n", bandingInfo.r_avg, bandingInfo.g_avg, bandingInfo.b_avg);

        final var srcImageToDebandFilename = "/Volumes/share-raid/Photo/film/2019/1908-1/mine/xe/debander/2022-01-02-0004-no-compression.tif";
//        final var srcImageToDebandFilename = "/Volumes/share-raid/Photo/film/2019/1908-1/mine/xe/debander/2022-01-02-0001-light-no-compression.tif";
        final TIFFImage outTiffImage = debander.deband(srcImageToDebandFilename, bandingInfo, 2.0);
        var trgImageToDebandFilename = new File("/Volumes/share-raid/Photo/film/2019/1908-1/mine/xe/2021-12-30-0013-debanded-using-gblur20-str2.tif");
//        var trgImageToDebandFilename = new File("/Volumes/share-raid/Photo/film/2019/1908-1/mine/xe/debander/2022-01-02-0001-light-no-compression-debanded.tif");
        TiffWriter.writeTiff(trgImageToDebandFilename, outTiffImage);
    }

    public BandingInfo generateBandingInfo(String lightFieldFilename) throws IOException {
        InputStream input = Files.newInputStream(Paths.get(lightFieldFilename));
        TIFFImage lightFieldTiffImage = TiffReader.readTiff(input);
        List<FileDirectory> lightFieldDirectories = lightFieldTiffImage.getFileDirectories();
        FileDirectory lightFieldDirectory = lightFieldDirectories.get(0);
        Rasters lightFieldRasters = lightFieldDirectory.readRasters();

        double r_avg = 0.0;
        double g_avg = 0.0;
        double b_avg = 0.0;

        int width = lightFieldRasters.getWidth();
        int height = lightFieldRasters.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Number[] pixel = lightFieldRasters.getPixel(x, y);
                int r = (int) pixel[0];
                int g = (int) pixel[1];
                int b = (int) pixel[2];
                r_avg += r;
                g_avg += g;
                b_avg += b;
            }
        }
        r_avg /= width * height;
        g_avg /= width * height;
        b_avg /= width * height;

        double[] r_x_mults = new double[width];
        double[] g_x_mults = new double[width];
        double[] b_x_mults = new double[width];
        double[] r_y_mults = new double[height];
        double[] g_y_mults = new double[height];
        double[] b_y_mults = new double[height];
        double[][] r_mults = new double[height][width];
        double[][] g_mults = new double[height][width];
        double[][] b_mults = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Number[] pixel = lightFieldRasters.getPixel(x, y);
                int r = (int) pixel[0];
                int g = (int) pixel[1];
                int b = (int) pixel[2];
                double r_mult = r / r_avg;
                r_x_mults[x] += r_mult;
                r_y_mults[y] += r_mult;
                double g_mult = g / g_avg;
                g_x_mults[x] += g_mult;
                g_y_mults[y] += g_mult;
                double b_mult = b / b_avg;
                b_x_mults[x] += b_mult;
                b_y_mults[y] += b_mult;
                r_mults[y][x] = r_mult;
                g_mults[y][x] = g_mult;
                b_mults[y][x] = b_mult;
            }
        }
        for (int x = 0; x < width; x++) {
            r_x_mults[x] /= height;
            g_x_mults[x] /= height;
            b_x_mults[x] /= height;
        }
        for (int y = 0; y < height; y++) {
            r_y_mults[y] /= width;
            g_y_mults[y] /= width;
            b_y_mults[y] /= width;
        }

        return new BandingInfo(r_avg, g_avg, b_avg,
                r_x_mults, g_x_mults, b_x_mults,
                r_y_mults, g_y_mults, b_y_mults,
                r_mults, g_mults, b_mults);
    }

    private TIFFImage deband(String srcImageToDebandFilename, BandingInfo bandingInfo, double strength) throws IOException {
        InputStream input = Files.newInputStream(Paths.get(srcImageToDebandFilename));
        TIFFImage imageToDeband = TiffReader.readTiff(input);
        List<FileDirectory> imageToDebandDirectories = imageToDeband.getFileDirectories();
        FileDirectory imageToDebandDirectory = imageToDebandDirectories.get(0);
        Rasters imageToDebandRasters = imageToDebandDirectory.readRasters();
        int width = imageToDebandRasters.getWidth();
        int height = imageToDebandRasters.getHeight();

        int samplesPerPixel = imageToDebandRasters.getSamplesPerPixel();
        FieldType fieldType = FieldType.SHORT;
        int bitsPerSample = fieldType.getBits();

        Rasters debandedRasters = new Rasters(width, height, samplesPerPixel, fieldType);

        int rowsPerStrip = debandedRasters.calculateRowsPerStrip(TiffConstants.PLANAR_CONFIGURATION_CHUNKY);

        FileDirectory debandedDirectory = new FileDirectory();
        debandedDirectory.setImageWidth(width);
        debandedDirectory.setImageHeight(height);
        debandedDirectory.setBitsPerSample(bitsPerSample);
//        debandedDirectory.setCompression(TiffConstants.COMPRESSION_NO);
        debandedDirectory.setCompression(TiffConstants.COMPRESSION_DEFLATE);
        debandedDirectory.setPhotometricInterpretation(TiffConstants.PHOTOMETRIC_INTERPRETATION_RGB);
        debandedDirectory.setSamplesPerPixel(samplesPerPixel);
        debandedDirectory.setRowsPerStrip(rowsPerStrip);
        debandedDirectory.setPlanarConfiguration(TiffConstants.PLANAR_CONFIGURATION_CHUNKY);
        debandedDirectory.setSampleFormat(TiffConstants.SAMPLE_FORMAT_UNSIGNED_INT);
        debandedDirectory.setWriteRasters(debandedRasters);


        for (int y = 0; y < height; y++) {
            for (int x = 0; x < Math.min(width, bandingInfo.r_x_mults.length); x++) {
                double r_x_mult = bandingInfo.r_x_mults[x];
                double g_x_mult = bandingInfo.g_x_mults[x];
                double b_x_mult = bandingInfo.b_x_mults[x];
                double r_y_mult = bandingInfo.r_y_mults[y];
                double g_y_mult = bandingInfo.g_y_mults[y];
                double b_y_mult = bandingInfo.b_y_mults[y];
                Number[] srcPixel = imageToDebandRasters.getPixel(x, y);
                int r = (int) srcPixel[0];
                int g = (int) srcPixel[1];
                int b = (int) srcPixel[2];

                int r_debanded = deband_pixel(r, strength * bandingInfo.r_mults[y][x]);
                int g_debanded = deband_pixel(g, strength * bandingInfo.g_mults[y][x]);
                int b_debanded = deband_pixel(b, strength * bandingInfo.b_mults[y][x]);
//                int r_debanded = (int)(32768 / r_x_mult / r_y_mult);
//                int g_debanded = (int)(32768 / g_x_mult / g_y_mult);
//                int b_debanded = (int)(32768 / b_x_mult / b_y_mult);
                debandedRasters.setPixelSample(0, x, y, r_debanded);
                debandedRasters.setPixelSample(1, x, y, g_debanded);
                debandedRasters.setPixelSample(2, x, y, b_debanded);
            }
        }
        TIFFImage debandedTiffImage = new TIFFImage();
        debandedTiffImage.add(debandedDirectory);
        return debandedTiffImage;
    }

    private int deband_pixel(int c, double c_mult) {
        int c_debanded = (int) (c / c_mult);
        if (c_debanded > 65535) {
            c_debanded = 65535;
        }
        return c_debanded;
    }
}
