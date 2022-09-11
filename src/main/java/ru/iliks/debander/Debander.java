package ru.iliks.debander;

import mil.nga.tiff.*;
import mil.nga.tiff.util.TiffConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

class BandingInfo {
    public final double[] channelAvgs;
    public final double[][][] channelMults;

    public BandingInfo(
            double[] channelAvgs,
            double[][][] channelMults
    ) {
        this.channelAvgs = channelAvgs;
        this.channelMults = channelMults;
    }
}

class TIFFAndRasters {
    public final TIFFImage tiffImage;
    public final Rasters rasters;

    TIFFAndRasters(TIFFImage tiffImage, Rasters rasters) {
        this.tiffImage = tiffImage;
        this.rasters = rasters;
    }
}

public class Debander {
    private static final Logger log = LoggerFactory.getLogger(Debander.class);
    private static final ExecutorService cpuExecSvc;
    private static final ExecutorService ioReadExecSvc;
    //why do we need separate write pool? because our pattern is like read-cpu process-write.
    //and we schedule "cpu process" and "write" processes after the "read" is finished. i.e. first we traverse all
    //found images in a dir and start their "read" process, i.e. fill the whole queue fo ioReadExecSvc. if we submitted
    //the "write" part to the same "read" executor, "write" operation for the first read image would be submitted to
    //the queue of executor when this queue already had all the "read" futures for all traversed images - i.e. they
    //will have to be stored in memory and we couldn't finish writing the first image until we've read all the input
    //images - only then would the common queue get to the first "write" task.
    private static final ExecutorService ioWriteExecSvc;
    private static final String DEBANDED_FILE_TOKEN = "-debanded.tif";

    static {
        int nCpuThreads = Runtime.getRuntime().availableProcessors();
        cpuExecSvc = Executors.newFixedThreadPool(nCpuThreads);
        log.info("Using {} threads for CPU operations", nCpuThreads);
        int nIoThreads = 4;
        ioReadExecSvc = Executors.newFixedThreadPool(nIoThreads);
        log.info("Using {} threads for read IO operations", nIoThreads);
        ioWriteExecSvc = Executors.newFixedThreadPool(nIoThreads);
        log.info("Using {} threads for write IO operations", nIoThreads);
    }

    public static void main(String[] args) throws IOException {
        final var lightFieldFilename = Paths.get("Z:\\Photo\\film\\2016\\1610-1702-1\\xes-vs\\raw0001.tif");
        final var dirWithImagesToDeband = Paths.get("Z:\\Photo\\film\\2016\\1610-1702-1\\xes-vs");
        try {
            final Instant start = Instant.now();
            debandDir(lightFieldFilename, dirWithImagesToDeband);
            final Instant finish = Instant.now();
            log.info("Done in {}", Duration.between(start, finish));
        } finally {
            cpuExecSvc.shutdown();
            ioReadExecSvc.shutdown();
            ioWriteExecSvc.shutdown();
        }
    }

    private static void debandDir(Path lightFieldFilename, Path dirWithImagesToDeband) throws IOException {
        final var debander = new Debander();
        final var futBandingInfo = debander.generateBandingInfo(lightFieldFilename)
                .whenCompleteAsync((res, ex) -> {
                    if (res != null) log.info("Avg levels per channel: {}\n", res.channelAvgs);
                }, ioWriteExecSvc);

        final var filesToDeband = new ArrayList<Path>();
        Files.walkFileTree(
                dirWithImagesToDeband,
                Collections.emptySet(), 1,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        var fname = file.getFileName().toString();
                        if (!fname.contains(DEBANDED_FILE_TOKEN) &&
                                (fname.endsWith("tiff") || fname.endsWith("tif") ||
                                        fname.endsWith("TIFF") || fname.endsWith("TIF"))) {
                            filesToDeband.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        final AtomicInteger numTotal = new AtomicInteger(filesToDeband.size());
        final AtomicInteger numProcessed = new AtomicInteger(0);
        final var futsDebanded = filesToDeband.stream().map(fileToDeband -> {
            final var futRastersToDeband =
                    CompletableFuture.supplyAsync(() -> debander.readRasters(fileToDeband), ioReadExecSvc);
            final CompletableFuture<TIFFImage> futDebandedTiff =
                    CompletableFuture.allOf(futBandingInfo, futRastersToDeband).thenApplyAsync(__ -> {
                        final TIFFAndRasters dataToDeband = futRastersToDeband.join();
                        final BandingInfo bandingInfo = futBandingInfo.join();
                        final var debandedRasters = debander.deband(dataToDeband.rasters, bandingInfo);
                        return generateTiffFromRasters(debandedRasters, dataToDeband);
                    }, cpuExecSvc);
            return futDebandedTiff.thenAcceptAsync(debandedTiff -> {
                        var dirname = fileToDeband.getParent();
                        var originalFilename = fileToDeband.getFileName().toString();
                        var idx = originalFilename.lastIndexOf('.');
                        var originalNameWoExtension = originalFilename.substring(0, idx);
                        var trgImageToDebandFilename = dirname.resolve(originalNameWoExtension + DEBANDED_FILE_TOKEN).toFile();
                        log.info("Writing fixed image {}", trgImageToDebandFilename);
                        try {
                            TiffWriter.writeTiff(trgImageToDebandFilename, debandedTiff);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, ioWriteExecSvc)
                    .handle((res, ex) -> {
                        if (ex != null) {
                            log.error("Skipping file {}", fileToDeband, ex);
                        }
                        final int processed = numProcessed.incrementAndGet();
                        log.info("Done {}, Progress: [{}/{}] ({}%)", fileToDeband, processed, numTotal.get(),
                                processed * 100.0/ numTotal.get());
                        return res;
                    });
        }).collect(Collectors.toList());

        CompletableFuture.allOf(futsDebanded.toArray(new CompletableFuture[0])).join();
    }

    private TIFFAndRasters readRasters(Path filename) {
        log.info("Reading TIFF file {}", filename);
        try {
            final InputStream input = Files.newInputStream(filename);
            final TIFFImage tiffImage = TiffReader.readTiff(input);
            final List<FileDirectory> directories = tiffImage.getFileDirectories();
            final FileDirectory directory = directories.get(0);
            final Rasters rasters = directory.readRasters();
            return new TIFFAndRasters(tiffImage, rasters);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<BandingInfo> generateBandingInfo(Path lightFieldFilename) {
        final CompletableFuture<TIFFAndRasters> futRasters =
                CompletableFuture.supplyAsync(() -> readRasters(lightFieldFilename), ioReadExecSvc);

        return futRasters.thenApplyAsync(lightFieldData -> {
            var lightFieldRasters = lightFieldData.rasters;
            final var numChannels = lightFieldRasters.getSamplesPerPixel();
            double[] channelAvg = new double[numChannels];

            int width = lightFieldRasters.getWidth();
            int height = lightFieldRasters.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    Number[] pixel = lightFieldRasters.getPixel(x, y);
                    for (int idxChannel = 0; idxChannel < pixel.length; idxChannel++) {
                        long sample = Integer.toUnsignedLong((int) pixel[idxChannel]);
                        channelAvg[idxChannel] += sample;
                    }
                }
            }
            //TODO: rewrite to avg of per-line averages to eliminate overflow possibility
            for (int i = 0; i < numChannels; i++) {
                channelAvg[i] /= width * height;
            }

            double[][][] channelMults = new double[numChannels][height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final Number[] pixel = lightFieldRasters.getPixel(x, y);
                    for (int idxChannel = 0; idxChannel < pixel.length; idxChannel++) {
                        final int sample = (int) pixel[idxChannel];
                        double mult = sample / channelAvg[idxChannel];
                        channelMults[idxChannel][y][x] = mult;

                    }
                }
            }
            return new BandingInfo(channelAvg, channelMults);
        }, cpuExecSvc);
    }

    private Rasters deband(Rasters rastersToDeband, BandingInfo bandingInfo) {
        final int width = rastersToDeband.getWidth();
        final int height = rastersToDeband.getHeight();
        final int samplesPerPixel = rastersToDeband.getSamplesPerPixel();
        final FieldType fieldType = FieldType.SHORT;
        final Rasters debandedRasters = new Rasters(width, height, samplesPerPixel, fieldType);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < Math.min(width, bandingInfo.channelMults[0][0].length); x++) {
                final Number[] srcPixel = rastersToDeband.getPixel(x, y);
                for (int idxChannel = 0; idxChannel < srcPixel.length; idxChannel++) {
                    final int sample = (int) srcPixel[idxChannel];
                    //NOTE: try this to visually see the light field in the debanded image. I did this and noticed that
                    //version 2.0.5 of tiff library produced digital garbage! it had 'similar' look but with lots of
                    //noise which affected metrics and debanding was poor! I upgraded to 3.0.0 and it became ok!
//                int r_debanded = bandingInfo.rr[y][x];
                    final int sample_debanded = deband_pixel(sample, bandingInfo.channelMults[idxChannel][y][x]);
                    debandedRasters.setPixelSample(idxChannel, x, y, sample_debanded);
                }
            }
        }
        return debandedRasters;
    }

    private int deband_pixel(int c, double c_mult) {
        int c_debanded = (int) (c / c_mult);
        if (c_debanded > 65535) {
            c_debanded = 65535;
        }
        return c_debanded;
    }

    private static TIFFImage generateTiffFromRasters(Rasters rasters, TIFFAndRasters originalDataToDeband) {
        var srcTiff = originalDataToDeband.tiffImage;
        var srcFileDir = srcTiff.getFileDirectory();
        final int rowsPerStrip = rasters.calculateRowsPerStrip(TiffConstants.PLANAR_CONFIGURATION_CHUNKY);
        final FileDirectory directory = new FileDirectory();
        directory.setImageWidth(rasters.getWidth());
        directory.setImageHeight(rasters.getHeight());
        directory.setBitsPerSample(rasters.getFieldTypes()[0].getBits());
//        directory.setCompression(TiffConstants.COMPRESSION_NO);
        directory.setCompression(TiffConstants.COMPRESSION_DEFLATE);
        directory.setPhotometricInterpretation(srcFileDir.getPhotometricInterpretation());
        directory.setSamplesPerPixel(rasters.getSamplesPerPixel());
        directory.setRowsPerStrip(rowsPerStrip);
        directory.setPlanarConfiguration(TiffConstants.PLANAR_CONFIGURATION_CHUNKY);
        directory.setSampleFormat(TiffConstants.SAMPLE_FORMAT_UNSIGNED_INT);
        directory.setWriteRasters(rasters);

        final TIFFImage tiffImage = new TIFFImage();
        tiffImage.add(directory);
        return tiffImage;
    }
}
