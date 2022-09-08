/*-
 * #%L
 * Expose the Imaris XT interface as an ImageJ2 service backed by ImgLib2.
 * %%
 * Copyright (C) 2019 - 2021 Bitplane AG
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.embl.mobie.io.ome.zarr;

import Imaris.IDataSetPrx;
import bdv.util.AxisOrder;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.SourceAndConverter;
import com.bitplane.xt.util.ColorTableUtils;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import net.imglib2.EuclideanSpace;
import net.imglib2.Volatile;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.display.ColorTable8;
import net.imglib2.img.Img;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import org.scijava.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An OME-Zarr backed image dataset
 * that can be visualised in ImageJ.
 *
 * @param <T> Type of the pixels
 * @param <V> Volatile type of the pixels
 */
public class SinglePyramidOMEZarrDataset< T extends NativeType< T > & RealType< T >, V extends Volatile< T > & NativeType< V > & RealType< V > > implements EuclideanSpace, OMEZarrDataset< T >
{
	/**
	 * The scijava context. This is needed (only) for creating {@link #ijDataset}.
	 */
	private final Context context;

	/**
	 * Dimensions of the dataset, and how they map to ImgLib2 dimensions.
	 */
	// TODO: Use n5-metadata?
	private final DatasetDimensions datasetDimensions;

	/**
	 * Physical calibration: size of voxel and min coordinate in X,Y,Z
	 * <p>
	 * Note, that the min coordinate is in ImgLib2 convention:
	 * it refers to the voxel center. This is in contrast to Imaris
	 * conventions, where it would indicate the min corner of the min
	 * voxel.
	 */
	// TODO: Use n5-metadata?
	private final DatasetCalibration calib;
	private final String zArrayPath;

	/**
	 * Whether the dataset is writable. If the dataset is not writable,
	 * modifying methods will throw an {@code UnsupportedOperationException}.
	 */
	private boolean writable;

	/**
	 * ImgPlus wrapping full resolution image.
	 * Metadata and color tables are set up according to TODO
	 * Lazily initialized.
	 */
	private ImgPlus< T > img;

	/**
	 * IJ2 Dataset wrapping {@link #img}.
	 * Lazily initialized.
	 */
	private Dataset ijDataset;

	/**
	 * List of sources, one for each channel of the dataset.
	 * The sources provide nested volatile versions.
	 * Lazily initialized.
	 */
	private List< SourceAndConverter< T > > sources;

	/**
	 * SpimData.
	 * Lazily initialized.
	 */
	private SpimData spimData;

	/**
	 * The imagePyramids serving the data
	 * to construct this dataset.
	 */
	private final Map< String, ZarrImagePyramid< T, V > > imagePyramids;

	/**
	 * Build a dataset from a single image pyramid.
	 * This constructor requires that the pyramid only contains
	 * a subset of the axes: X,Y,Z,C,T
	 *
	 * TODO add constructors for combining several pyramids
	 * TODO add constructors for slicing a pyramid
	 *
	 * @param context The SciJava context for building the SciJava dataset
	 * @param imagePyramid The imagePyramid that contains all data.
	 * @throws Error
	 */
	SinglePyramidOMEZarrDataset(
			final Context context,
			final String name,
			final ZarrImagePyramid< T, V > imagePyramid ) throws Error
	{
		this.context = context;
		this.imagePyramids = new HashMap<>();
		imagePyramids.put( name, imagePyramid );
	}

	/**
	 * Transfer Imaris channel min/max settings to ImgPlus.
	 */
	private void updateImpChannelMinMax() throws Error
	{
		final int sc = datasetDimensions.getImarisDimensions()[ 3 ];
		for ( int c = 0; c < sc; ++c )
		{
			final double min = zArrayPath.GetChannelRangeMin( c );
			final double max = zArrayPath.GetChannelRangeMax( c );
			imp.setChannelMinimum( c, min );
			imp.setChannelMaximum( c, max );
		}
	}

	/**
	 * Create/update color tables for ImgPlus.
	 */
	private void updateImpColorTables() throws Error
	{
		final int[] imarisDimensions = datasetDimensions.getImarisDimensions();
		final int sz = imarisDimensions[ 2 ];
		final int sc = imarisDimensions[ 3 ];
		final int st = imarisDimensions[ 4 ];
		imp.initializeColorTables( sc * sz * st );
		for ( int c = 0; c < sc; ++c )
		{
			final ColorTable8 cT = ColorTableUtils.createChannelColorTable( zArrayPath, c );
			for ( int t = 0; t < st; ++t )
				for ( int z = 0; z < sz; ++z )
					imp.setColorTable( cT, z + sz * ( c + sc * t ) );
		}
	}

	/**
	 * Create/update calibrated axes for ImgPlus.
	 */
	private void updateImpAxes()
	{
		final ArrayList< CalibratedAxis > axes = new ArrayList<>();
		axes.add( new DefaultLinearAxis( Axes.X, calib.unit(), calib.voxelSize( 0 ) ) );
		axes.add( new DefaultLinearAxis( Axes.Y, calib.unit(), calib.voxelSize( 1 ) ) );
		final AxisOrder axisOrder = datasetDimensions.getAxisOrder();
		if ( axisOrder.hasZ() )
			axes.add( new DefaultLinearAxis( Axes.Z, calib.unit(), calib.voxelSize( 2 ) ) );
		if ( axisOrder.hasChannels() )
			axes.add( new DefaultLinearAxis( Axes.CHANNEL ) );
		if ( axisOrder.hasTimepoints() )
			axes.add( new DefaultLinearAxis( Axes.TIME ) );

		for ( int i = 0; i < axes.size(); ++i )
			imp.setAxis( axes.get( i ), i );
	}

	private void ensureWritable()
	{
		if ( !writable )
			throw new UnsupportedOperationException( "This dataset is not writable" );
	}

	/**
	 * Sets unit, voxel size, and min coordinate from Imaris extents.
	 * <p>
	 * Note, that the given min/max extents are in Imaris conventions: {@code
	 * extendMinX} refers to the min corner of the min voxel of the dataset,
	 * {@code extendMaxX} refers to the max corner of the max voxel of the
	 * dataset.
	 * <p>
	 * This is in contrast to the ImgLib2 convention, where coordinates always
	 * refer to the voxel center.
	 */
	public void setCalibration(
			final String unit,
			final float extendMinX,
			final float extendMaxX,
			final float extendMinY,
			final float extendMaxY,
			final float extendMinZ,
			final float extendMaxZ ) throws Error // TODO: revise exception handling
	{
		ensureWritable();
		final int[] size = datasetDimensions.getImarisDimensions();
		calib.setExtends( unit, extendMinX, extendMaxX, extendMinY, extendMaxY, extendMinZ, extendMaxZ, size );
		updateSourceCalibrations();
		updateImpAxes();
		calib.applyToDataset( zArrayPath, size );
	}

	/**
	 * Set unit and voxel size.
	 * (The min coordinate is not modified).
	 */
	public void setCalibration( final VoxelDimensions voxelDimensions ) throws Error
	{
		ensureWritable();
		calib.setVoxelDimensions( voxelDimensions );
		updateSourceCalibrations();
		updateImpAxes();
		final int[] size = datasetDimensions.getImarisDimensions();
		calib.applyToDataset( zArrayPath, size );
	}

	/**
	 * Sets unit, voxel size, and min coordinate.
	 * <p>
	 * Note, that the min coordinate is in ImgLib2 convention: It refers to the
	 * voxel center. This is in contrast to Imaris conventions, where {@code
	 * ExtendMinX, ExtendMinY, ExtendMinZ} indicate the min corner of the min voxel.
	 * <p>
	 * This method translates the given min coordinate (etc) to Imaris {@code
	 * extendMin/Max} extents.
	 */
	public void setCalibration( final VoxelDimensions voxelDimensions,
			final double minX,
			final double minY,
			final double minZ ) throws Error
	{
		ensureWritable();
		calib.setMin( minX, minY, minZ );
		calib.setVoxelDimensions( voxelDimensions );
		updateSourceCalibrations();
		updateImpAxes();
		final int[] size = datasetDimensions.getImarisDimensions();
		calib.applyToDataset( zArrayPath, size );
	}

	/**
	 * Sets unit, voxel size, and min coordinate.
	 * <p>
	 * Note, that the min coordinate is in ImgLib2 convention: It refers to the
	 * voxel center. This is in contrast to Imaris conventions, where {@code
	 * ExtendMinX, ExtendMinY, ExtendMinZ} indicate the min corner of the min voxel.
	 * <p>
	 * This method translates the given min coordinate (etc) to Imaris {@code
	 * extendMin/Max} extents.
	 */
	public void setCalibration( final DatasetCalibration calibration ) throws Error
	{
		ensureWritable();
		calib.set( calibration );
		updateSourceCalibrations();
		updateImpAxes();
		final int[] size = datasetDimensions.getImarisDimensions();
		calib.applyToDataset( zArrayPath, size );
	}

	/**
	 * Set the modification flag of the Imaris dataset.
	 * <p>
	 * Imaris asks whether to save a modified dataset, if {@code modified=true}.
	 * Set {@code modified=false}, if you want Imaris to terminate without
	 * prompting.
	 */
	public void setModified( final boolean modified ) throws Error
	{
		ensureWritable();
		zArrayPath.SetModified( modified );
	}

	private CachedCellImg< T, ? > asImg( String zArrayPath )
	{
		final Img< T > img = asImg();
		imp = new ImgPlus<>( img );
		imp.setName( getName() );
		updateImpAxes();
		updateImpColorTables();
		updateImpChannelMinMax();
		return imagePyramid.getImg( 0 );
		return null;
	}

	/**
	 * Get an {@code ImgPlus} wrapping the full resolution image (see {@link
	 * #asImg}). Metadata and color tables are set up according to Imaris (at
	 * the time of construction of this {@code ImarisDataset}).
	 */
	public ImgPlus< T > asImgPlus()
	{
		initImg();
		return img;
	}

	@Override
	public Dataset asDataset()
	{
		initImg();

		synchronized ( img )
		{
			if ( ijDataset == null )
			{
				final DatasetService datasetService = context.getService( DatasetService.class );
				ijDataset = datasetService.create( img );
				ijDataset.setName( img.getName() );
				ijDataset.setRGBMerged( false );
			}
			return ijDataset;
		}
	}


	@Override
	public List< SourceAndConverter< T > > asSources()
	{
		return sources;
	}

	@Override
	public SpimData asSpimData()
	{
		return spimData;
	}

	@Override
	public int numChannels()
	{
		return 0; // TODO
	}

	@Override
	public int numTimePoints()
	{
		return 0; // TODO
	}

	private void initImg()
	{
		if ( img != null ) return;

		// TODO
	}

	@Override
	public int numDimensions()
	{
		return datasetDimensions.getAxisOrder().numDimensions();
	}

	/**
	 * Get the number of levels in the resolution pyramid.
	 */
	public int numResolutions()
	{
		return imagePyramid.numResolutions();
	}

	/**
	 * Get the number timepoints.
	 */
	@Override
	public int numTimepoints()
	{
		return imagePyramid.numTimepoints();
	}

	/**
	 * Get an instance of the pixel type.
	 */
	@Override
	public T getType()
	{
		return imagePyramid.getType();
	}

	/**
	 * Get the size of the underlying
	 * 5D Imaris dataset and the mapping to dimensions of the ImgLib2
	 * representation.
	 */
	public DatasetDimensions getDatasetDimensions()
	{
		return datasetDimensions;
	}

	/**
	 * Get the physical calibration: unit, voxel size, and min in XYZ.
	 */
	public DatasetCalibration getCalibration()
	{
		return calib.copy();
	}

	/**
	 * Get the base color of a channel.
	 *
	 * @param channel index of the channel
	 * @return channel color
	 */
	public ARGBType getChannelColor( final int channel ) throws Error
	{
		return ColorTableUtils.getChannelColor( zArrayPath, channel );
	}

	/**
	 * Get the {@code "Image > Filename"} parameter of the dataset.
	 */
	public String getFilename() throws Error
	{
		return zArrayPath.GetParameter( "Image", "Filename" );
	}

	/**
	 * Get the {@code "Image > Name"} parameter of the dataset.
	 */
	public String getName() throws Error
	{
		return zArrayPath.GetParameter("Image", "Name");
	}

	/**
	 * Get the underlying {@code IDataSet} ICE proxy.
	 */
	public IDataSetPrx getIDataSetPrx()
	{
		return zArrayPath;
	}

	/**
	 * Persist all modifications back to Imaris.
	 */
	public void persist()
	{
		ensureWritable();
		this.imagePyramid.persist();
	}

	/**
	 * Invalidate cache for all levels of the resolution pyramid, except the
	 * full resolution. This is necessary when modifying a dataset and at the
	 * same time visualizing it in BigDataViewer. (This scenario is not very
	 * likely in practice, but still...)
	 * <p>
	 * While actual modifications to the full-resolution image are immediately
	 * visible, updating the resolution pyramid needs to go through Imaris. That
	 * is, after making modifications, first {@link #persist()} should be called
	 * to ensure all changes have been transferred to Imaris. Second, the
	 * dataset should be visible in Imaris, so that Imaris recomputes the
	 * resolution pyramid. Finally, the lower-resolution images on the ImgLib2
	 * side should be invalidated (using this method), so the recomputed pyramid
	 * data is fetched from Imaris.
	 */
	public void invalidatePyramid()
	{
		this.imagePyramid.invalidate();
	}
}
