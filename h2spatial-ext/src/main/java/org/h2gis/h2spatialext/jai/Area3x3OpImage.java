package org.h2gis.h2spatialext.jai;

import javax.media.jai.AreaOpImage;
import javax.media.jai.BorderExtender;
import javax.media.jai.ImageLayout;
import javax.media.jai.RasterAccessor;
import javax.media.jai.RasterFormatTag;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 3x3 computing helper. Do the computation in double scale, in order to avoid code redundancy.
 * TODO Thread worker pool in order to use all cores
 * @author Nicolas Fortin
 */
public abstract class Area3x3OpImage extends AreaOpImage {
    public static final Point[] NEIGHBORS_INDEX = new Point[] {
            new Point(-1, -1), // top left
            new Point(0, -1), // top
            new Point(1, -1), // top right
            new Point(-1, 0), // left
            new Point(0, 0), // center
            new Point(1, 0), // right
            new Point(-1, 1), // bottom left
            new Point(0, 1), // bottom
            new Point(1, 1) // bottom right
    };
    // in {@link NEIGHBORS_INDEX} the index of center.
    public static final int SRC_INDEX = 4;
    public Area3x3OpImage(RenderedImage source, BorderExtender extender, Map config, ImageLayout layout) {
        // Require 1 neighbors around the source pixel
        super(source, layout, config, true, extender, 1, 1, 1, 1);
    }

    @Override
    protected void computeRect(Raster[] sources, WritableRaster dest, Rectangle destRect) {
        // Retrieve format tags.
        RasterFormatTag[] formatTags = getFormatTags();
        Rectangle srcRect = mapDestRect(destRect, 0);

        List<RasterAccessor> rasterAccessList = new ArrayList<RasterAccessor>(sources.length);
        int srcIndex = 0;
        for(Raster source : sources) {
                rasterAccessList.add(new RasterAccessor(source, srcRect, formatTags[srcIndex], getSourceImage(srcIndex)
                        .getColorModel()));
            srcIndex++;
        }
        // last tag id is for destination
        RasterAccessor dst = new RasterAccessor(dest, destRect, formatTags[sources.length], getColorModel());

        switch (dst.getDataType()) {
            case DataBuffer.TYPE_FLOAT:
                processingFloatDest(rasterAccessList, dst);
                break;
            case DataBuffer.TYPE_INT:
                processingIntDest(rasterAccessList, dst);
                break;
            case DataBuffer.TYPE_BYTE:
                processingByteDest(rasterAccessList, dst);
                break;
            case DataBuffer.TYPE_SHORT:
                processingShortDest(rasterAccessList, dst);
                break;
            case DataBuffer.TYPE_DOUBLE:
                processingDoubleDest(rasterAccessList, dst);
                break;
        }
    }

    protected static SrcDataStruct dataStructFromRasterAccessor(RasterAccessor rasterAccessor) {
        switch (rasterAccessor.getDataType())  {
            case DataBuffer.TYPE_FLOAT:
                return new SrcDataStructFloat(rasterAccessor);
            case DataBuffer.TYPE_INT:
                return new SrcDataStructInt(rasterAccessor);
            case DataBuffer.TYPE_BYTE:
                return new SrcDataStructByte(rasterAccessor);
            case DataBuffer.TYPE_SHORT:
                return new SrcDataStructShort(rasterAccessor);
            default:
                return new SrcDataStructDouble(rasterAccessor);
        }
    }

    protected void processingDoubleDest(List<RasterAccessor> rasterAccess, RasterAccessor dst) {

        final int destWidth = dst.getWidth();
        final int destHeight = dst.getHeight();
        final int destNumBands = dst.getNumBands();

        final double destDataArrays[][] = dst.getDoubleDataArrays();
        final int destBandOffsets[] = dst.getBandOffsets();
        final int destPixelStride = dst.getPixelStride();
        final int dstScanlineStride = dst.getScanlineStride();

        final List<SrcDataStruct> srcDataStructs = new ArrayList<SrcDataStruct>(rasterAccess.size());
        for(RasterAccessor rasterAccessor : rasterAccess) {
            srcDataStructs.add(dataStructFromRasterAccessor(rasterAccessor));
        }

        for(int idBand = 0; idBand < destNumBands; idBand++) {
            final double dstData[] = destDataArrays[idBand];
            int dstScanlineOffset = destBandOffsets[idBand];
            // Init
            for (int j = 0; j < destHeight; j++) {
                int dstPixelOffset = dstScanlineOffset;
                for (int i = 0; i < destWidth; i++) {
                    double[][] neighborsValues = new double[srcDataStructs.size()][];
                    for(int idSrc=0; idSrc < neighborsValues.length; idSrc++) {
                        neighborsValues[idSrc] = srcDataStructs.get(idSrc).getNeighborsValues(idBand, i, j);
                    }
                    // Compute in sub method
                    dstData[dstPixelOffset] = computeCell(idBand, neighborsValues);
                    dstPixelOffset += destPixelStride;
                }
                dstScanlineOffset += dstScanlineStride;
            }
        }
        // If the RasterAccessor object set up a temporary buffer for the
        // op to write to, tell the RasterAccessor to write that data
        // to the raster no that we're done with it.
        if (dst.isDataCopy()) {
            dst.clampDataArrays();
            dst.copyDataToRaster();
        }
    }

    private interface SrcDataStruct {
        /**
         * Get neighbors value around cell i,j
         * @param band source band
         * @param i source column
         * @param j source row
         * @return neighbors values
         */
        double[] getNeighborsValues(int band, int i, int j);
    }

    private static class SrcDataStructDouble implements SrcDataStruct {

        public SrcDataStructDouble(RasterAccessor rasterAccess) {
            srcDataArrays = rasterAccess.getDoubleDataArrays();
            srcBandOffsets = rasterAccess.getBandOffsets();
            srcPixelStride = rasterAccess.getPixelStride();
            srcScanlineStride = rasterAccess.getScanlineStride();
            destNumBands = rasterAccess.getNumBands();
            rightPixelOffset = rasterAccess.getNumBands() * 2;
            bottomScanlineOffset = srcScanlineStride * 2;
        }

        final double srcDataArrays[][];
        final int srcBandOffsets[];
        final int srcPixelStride;
        final int srcScanlineStride;
        final int rightPixelOffset;
        final int destNumBands;
        final int bottomScanlineOffset;

        @Override
        public double[] getNeighborsValues(int band, int i, int j) {
            double[] srcData = srcDataArrays[band];
            int srcPixelOffset = srcBandOffsets[band] + j * srcScanlineStride + i * srcPixelStride;
            return new double[]{ srcData[srcPixelOffset], // top left
                    srcData[srcPixelOffset + destNumBands], // top
                    srcData[srcPixelOffset + rightPixelOffset], // top right
                    srcData[srcPixelOffset + srcScanlineStride], // left
                    srcData[srcPixelOffset + srcScanlineStride + destNumBands], //center
                    srcData[srcPixelOffset + srcScanlineStride + rightPixelOffset], // right
                    srcData[srcPixelOffset + bottomScanlineOffset], // bottom left
                    srcData[srcPixelOffset + bottomScanlineOffset + destNumBands], // bottom
                    srcData[srcPixelOffset + bottomScanlineOffset + rightPixelOffset] // bottom right
            };
        }
    }


    protected void processingShortDest(List<RasterAccessor> rasterAccess, RasterAccessor dst) {

        final int destWidth = dst.getWidth();
        final int destHeight = dst.getHeight();
        final int destNumBands = dst.getNumBands();

        final short destDataArrays[][] = dst.getShortDataArrays();
        final int destBandOffsets[] = dst.getBandOffsets();
        final int destPixelStride = dst.getPixelStride();
        final int dstScanlineStride = dst.getScanlineStride();

        final List<SrcDataStruct> srcDataStructs = new ArrayList<SrcDataStruct>(rasterAccess.size());
        for(RasterAccessor rasterAccessor : rasterAccess) {
            srcDataStructs.add(dataStructFromRasterAccessor(rasterAccessor));
        }

        for(int idBand = 0; idBand < destNumBands; idBand++) {
            final short dstData[] = destDataArrays[idBand];
            int dstScanlineOffset = destBandOffsets[idBand];
            // Init
            for (int j = 0; j < destHeight; j++) {
                int dstPixelOffset = dstScanlineOffset;
                for (int i = 0; i < destWidth; i++) {
                    double[][] neighborsValues = new double[srcDataStructs.size()][];
                    for(int idSrc=0; idSrc < neighborsValues.length; idSrc++) {
                        neighborsValues[idSrc] = srcDataStructs.get(idSrc).getNeighborsValues(idBand, i, j);
                    }
                    // Compute in sub method
                    double value = computeCell(idBand, neighborsValues);
                    dstData[dstPixelOffset] = (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
                    dstPixelOffset += destPixelStride;
                }
                dstScanlineOffset += dstScanlineStride;
            }
        }
        // If the RasterAccessor object set up a temporary buffer for the
        // op to write to, tell the RasterAccessor to write that data
        // to the raster no that we're done with it.
        if (dst.isDataCopy()) {
            dst.clampDataArrays();
            dst.copyDataToRaster();
        }
    }

    private static class SrcDataStructShort implements SrcDataStruct{

        public SrcDataStructShort(RasterAccessor rasterAccess) {
            srcDataArrays = rasterAccess.getShortDataArrays();
            srcBandOffsets = rasterAccess.getBandOffsets();
            srcPixelStride = rasterAccess.getPixelStride();
            srcScanlineStride = rasterAccess.getScanlineStride();
            destNumBands = rasterAccess.getNumBands();
            rightPixelOffset = rasterAccess.getNumBands() * 2;
            bottomScanlineOffset = srcScanlineStride * 2;
        }

        final short srcDataArrays[][];
        final int srcBandOffsets[];
        final int srcPixelStride;
        final int srcScanlineStride;
        final int rightPixelOffset;
        final int destNumBands;
        final int bottomScanlineOffset;


        @Override
        public double[] getNeighborsValues(int band, int i, int j) {
            short[] srcData = srcDataArrays[band];
            int srcPixelOffset = srcBandOffsets[band] + j * srcScanlineStride + i * srcPixelStride;
            return new double[]{ srcData[srcPixelOffset], // top left
                    srcData[srcPixelOffset + destNumBands], // top
                    srcData[srcPixelOffset + rightPixelOffset], // top right
                    srcData[srcPixelOffset + srcScanlineStride], // left
                    srcData[srcPixelOffset + srcScanlineStride + destNumBands], //center
                    srcData[srcPixelOffset + srcScanlineStride + rightPixelOffset], // right
                    srcData[srcPixelOffset + bottomScanlineOffset], // bottom left
                    srcData[srcPixelOffset + bottomScanlineOffset + destNumBands], // bottom
                    srcData[srcPixelOffset + bottomScanlineOffset + rightPixelOffset] // bottom right
            };
        }
    }


    protected void processingByteDest(List<RasterAccessor> rasterAccess, RasterAccessor dst) {

        final int destWidth = dst.getWidth();
        final int destHeight = dst.getHeight();
        final int destNumBands = dst.getNumBands();

        final byte destDataArrays[][] = dst.getByteDataArrays();
        final int destBandOffsets[] = dst.getBandOffsets();
        final int destPixelStride = dst.getPixelStride();
        final int dstScanlineStride = dst.getScanlineStride();

        final List<SrcDataStruct> srcDataStructs = new ArrayList<SrcDataStruct>(rasterAccess.size());
        for(RasterAccessor rasterAccessor : rasterAccess) {
            srcDataStructs.add(dataStructFromRasterAccessor(rasterAccessor));
        }

        for(int idBand = 0; idBand < destNumBands; idBand++) {
            final byte dstData[] = destDataArrays[idBand];
            int dstScanlineOffset = destBandOffsets[idBand];
            // Init
            for (int j = 0; j < destHeight; j++) {
                int dstPixelOffset = dstScanlineOffset;
                for (int i = 0; i < destWidth; i++) {
                    double[][] neighborsValues = new double[srcDataStructs.size()][];
                    for(int idSrc=0; idSrc < neighborsValues.length; idSrc++) {
                        neighborsValues[idSrc] = srcDataStructs.get(idSrc).getNeighborsValues(idBand, i, j);
                    }
                    // Compute in sub method
                    double value = computeCell(idBand, neighborsValues);
                    dstData[dstPixelOffset] =  (byte)Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, value));
                    dstPixelOffset += destPixelStride;
                }
                dstScanlineOffset += dstScanlineStride;
            }
        }
        // If the RasterAccessor object set up a temporary buffer for the
        // op to write to, tell the RasterAccessor to write that data
        // to the raster no that we're done with it.
        if (dst.isDataCopy()) {
            dst.clampDataArrays();
            dst.copyDataToRaster();
        }
    }

    private static class SrcDataStructByte implements SrcDataStruct {

        public SrcDataStructByte(RasterAccessor rasterAccess) {
            srcDataArrays = rasterAccess.getByteDataArrays();
            srcBandOffsets = rasterAccess.getBandOffsets();
            srcPixelStride = rasterAccess.getPixelStride();
            srcScanlineStride = rasterAccess.getScanlineStride();
            destNumBands = rasterAccess.getNumBands();
            rightPixelOffset = rasterAccess.getNumBands() * 2;
            bottomScanlineOffset = srcScanlineStride * 2;
        }

        final byte srcDataArrays[][];
        final int srcBandOffsets[];
        final int srcPixelStride;
        final int srcScanlineStride;
        final int rightPixelOffset;
        final int destNumBands;
        final int bottomScanlineOffset;


        @Override
        public double[] getNeighborsValues(int band, int i, int j) {
            byte[] srcData = srcDataArrays[band];
            int srcPixelOffset = srcBandOffsets[band] + j * srcScanlineStride + i * srcPixelStride;
            return new double[]{ srcData[srcPixelOffset] & 0xff, // top left
                    srcData[srcPixelOffset + destNumBands] & 0xff, // top
                    srcData[srcPixelOffset + rightPixelOffset] & 0xff, // top right
                    srcData[srcPixelOffset + srcScanlineStride] & 0xff, // left
                    srcData[srcPixelOffset + srcScanlineStride + destNumBands] & 0xff, //center
                    srcData[srcPixelOffset + srcScanlineStride + rightPixelOffset] & 0xff, // right
                    srcData[srcPixelOffset + bottomScanlineOffset] & 0xff, // bottom left
                    srcData[srcPixelOffset + bottomScanlineOffset + destNumBands] & 0xff, // bottom
                    srcData[srcPixelOffset + bottomScanlineOffset + rightPixelOffset] & 0xff // bottom right
            };
        }
    }

    protected void processingFloatDest(List<RasterAccessor> rasterAccess, RasterAccessor dst) {

        final int destWidth = dst.getWidth();
        final int destHeight = dst.getHeight();
        final int destNumBands = dst.getNumBands();

        final float destDataArrays[][] = dst.getFloatDataArrays();
        final int destBandOffsets[] = dst.getBandOffsets();
        final int destPixelStride = dst.getPixelStride();
        final int dstScanlineStride = dst.getScanlineStride();

        final List<SrcDataStruct> srcDataStructs = new ArrayList<SrcDataStruct>(rasterAccess.size());
        for(RasterAccessor rasterAccessor : rasterAccess) {
            srcDataStructs.add(dataStructFromRasterAccessor(rasterAccessor));
        }

        for(int idBand = 0; idBand < destNumBands; idBand++) {
            final float dstData[] = destDataArrays[idBand];
            int dstScanlineOffset = destBandOffsets[idBand];
            // Init
            for (int j = 0; j < destHeight; j++) {
                int dstPixelOffset = dstScanlineOffset;
                for (int i = 0; i < destWidth; i++) {
                    double[][] neighborsValues = new double[srcDataStructs.size()][];
                    for(int idSrc=0; idSrc < neighborsValues.length; idSrc++) {
                        neighborsValues[idSrc] = srcDataStructs.get(idSrc).getNeighborsValues(idBand, i, j);
                    }
                    // Compute in sub method
                    dstData[dstPixelOffset] = (float) computeCell(idBand, neighborsValues);
                    dstPixelOffset += destPixelStride;
                }
                dstScanlineOffset += dstScanlineStride;
            }
        }
        // If the RasterAccessor object set up a temporary buffer for the
        // op to write to, tell the RasterAccessor to write that data
        // to the raster no that we're done with it.
        if (dst.isDataCopy()) {
            dst.clampDataArrays();
            dst.copyDataToRaster();
        }
    }

    private static class SrcDataStructInt implements SrcDataStruct{

        public SrcDataStructInt(RasterAccessor rasterAccess) {
            srcDataArrays = rasterAccess.getIntDataArrays();
            srcBandOffsets = rasterAccess.getBandOffsets();
            srcPixelStride = rasterAccess.getPixelStride();
            srcScanlineStride = rasterAccess.getScanlineStride();
            destNumBands = rasterAccess.getNumBands();
            rightPixelOffset = rasterAccess.getNumBands() * 2;
            bottomScanlineOffset = srcScanlineStride * 2;
        }

        final int srcDataArrays[][];
        final int srcBandOffsets[];
        final int srcPixelStride;
        final int srcScanlineStride;
        final int rightPixelOffset;
        final int destNumBands;
        final int bottomScanlineOffset;


        @Override
        public double[] getNeighborsValues(int band, int i, int j) {
            int[] srcData = srcDataArrays[band];
            int srcPixelOffset = srcBandOffsets[band] + j * srcScanlineStride + i * srcPixelStride;
            return new double[]{ srcData[srcPixelOffset], // top left
                    srcData[srcPixelOffset + destNumBands], // top
                    srcData[srcPixelOffset + rightPixelOffset], // top right
                    srcData[srcPixelOffset + srcScanlineStride], // left
                    srcData[srcPixelOffset + srcScanlineStride + destNumBands], //center
                    srcData[srcPixelOffset + srcScanlineStride + rightPixelOffset], // right
                    srcData[srcPixelOffset + bottomScanlineOffset], // bottom left
                    srcData[srcPixelOffset + bottomScanlineOffset + destNumBands], // bottom
                    srcData[srcPixelOffset + bottomScanlineOffset + rightPixelOffset] // bottom right
            };
        }
    }
    protected void processingIntDest(List<RasterAccessor> rasterAccess, RasterAccessor dst) {

        final int destWidth = dst.getWidth();
        final int destHeight = dst.getHeight();
        final int destNumBands = dst.getNumBands();

        final int destDataArrays[][] = dst.getIntDataArrays();
        final int destBandOffsets[] = dst.getBandOffsets();
        final int destPixelStride = dst.getPixelStride();
        final int dstScanlineStride = dst.getScanlineStride();

        final List<SrcDataStruct> srcDataStructs = new ArrayList<SrcDataStruct>(rasterAccess.size());
        for(RasterAccessor rasterAccessor : rasterAccess) {
            srcDataStructs.add(dataStructFromRasterAccessor(rasterAccessor));
        }

        for(int idBand = 0; idBand < destNumBands; idBand++) {
            final int dstData[] = destDataArrays[idBand];
            int dstScanlineOffset = destBandOffsets[idBand];
            // Init
            for (int j = 0; j < destHeight; j++) {
                int dstPixelOffset = dstScanlineOffset;
                for (int i = 0; i < destWidth; i++) {
                    double[][] neighborsValues = new double[srcDataStructs.size()][];
                    for(int idSrc=0; idSrc < neighborsValues.length; idSrc++) {
                        neighborsValues[idSrc] = srcDataStructs.get(idSrc).getNeighborsValues(idBand, i, j);
                    }
                    // Compute in sub method
                    dstData[dstPixelOffset] = (int) computeCell(idBand, neighborsValues);
                    dstPixelOffset += destPixelStride;
                }
                dstScanlineOffset += dstScanlineStride;
            }
        }
        // If the RasterAccessor object set up a temporary buffer for the
        // op to write to, tell the RasterAccessor to write that data
        // to the raster no that we're done with it.
        if (dst.isDataCopy()) {
            dst.clampDataArrays();
            dst.copyDataToRaster();
        }
    }

    private static class SrcDataStructFloat implements SrcDataStruct {

        public SrcDataStructFloat(RasterAccessor rasterAccess) {
            srcDataArrays = rasterAccess.getFloatDataArrays();
            srcBandOffsets = rasterAccess.getBandOffsets();
            srcPixelStride = rasterAccess.getPixelStride();
            srcScanlineStride = rasterAccess.getScanlineStride();
            destNumBands = rasterAccess.getNumBands();
            rightPixelOffset = rasterAccess.getNumBands() * 2;
            bottomScanlineOffset = srcScanlineStride * 2;
        }

        final float srcDataArrays[][];
        final int srcBandOffsets[];
        final int srcPixelStride;
        final int srcScanlineStride;
        final int rightPixelOffset;
        final int destNumBands;
        final int bottomScanlineOffset;


        @Override
        public double[] getNeighborsValues(int band, int i, int j) {
            float[] srcData = srcDataArrays[band];
            int srcPixelOffset = srcBandOffsets[band] + j * srcScanlineStride + i * srcPixelStride;
            return new double[]{ srcData[srcPixelOffset], // top left
                    srcData[srcPixelOffset + destNumBands], // top
                    srcData[srcPixelOffset + rightPixelOffset], // top right
                    srcData[srcPixelOffset + srcScanlineStride], // left
                    srcData[srcPixelOffset + srcScanlineStride + destNumBands], //center
                    srcData[srcPixelOffset + srcScanlineStride + rightPixelOffset], // right
                    srcData[srcPixelOffset + bottomScanlineOffset], // bottom left
                    srcData[srcPixelOffset + bottomScanlineOffset + destNumBands], // bottom
                    srcData[srcPixelOffset + bottomScanlineOffset + rightPixelOffset] // bottom right
            };
        }
    }

    protected abstract double computeCell(int band, final double[][] neighborsValues);
}