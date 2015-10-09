package it.polimi.modaclouds.scalingsdatests.validator.util;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.labels.CategoryItemLabelGenerator;
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryMarker;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.category.CategoryItemRenderer;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.ui.RectangleAnchor;
import org.jfree.ui.TextAnchor;

public class GenericChart<E> extends JPanel {
	private static final long serialVersionUID = -7208222758877437049L;
	
	private JFreeChart graph;
	private JLabel label;
//	private boolean validLabel = false;
	public E dataset;
	
	private String title;
	private String x, y;
	public boolean pointsVisible = true;
	public boolean labelsVisible = true;
	
	public double shownValsBelowMin = 1.0;
	public double shownValsAboveMax = 1.0;
	
	public Double exactMin = null;
	public Double exactMax = null;
	
	public boolean defaultRange = false;
	
	
	
	public static class Marker {
		double position;
		String label;
		String hiddenLabel;
		boolean isVertical = false;
		
		public Marker(double position, String label) {
			this.position = position;
			this.label = label;
		}
		
		public Marker(double position) {
			this(position, "");
		}
	}
	
	public List<Marker> markers;
	
	public void addMarker(double position, String label) {
		for (Marker m : markers) {
			if (m.position == position) {
				if (m.hiddenLabel == null || m.hiddenLabel.equals(""))
					m.hiddenLabel = m.label;
				
				if (m.label == null || m.label.equals(""))
					m.label = label;
				else if (label != null && !label.equals(""))
					m.label += ", " + label;
				
				Marker newm = new Marker(position);
				newm.hiddenLabel = label;
				markers.add(newm);
				
				return;
			}
		}
		markers.add(new Marker(position, label));
	}
	
	public GenericChart(String title, String x, String y) {
		super();
		
		this.title = title;
		this.x = x;
		this.y = y;
		
		label = new JLabel();
		label.setIcon(null);
		add(label);		
		markers = new ArrayList<Marker>();
	//	validLabel = true;
	}
	
	public GenericChart(String x, String y) {
		this(null, x, y);
	}
	
	public GenericChart() {
		this(null, "X", "Y");
	}
	
	@SuppressWarnings("unchecked")
	public void initDataset(Class<?> type) throws InstantiationException, IllegalAccessException {
		dataset = (E) type.newInstance();
//		validLabel = false;
	}
	
	public void initDataset() {
		try {
			initDataset(DefaultCategoryDataset.class);
//			validLabel=false;
		} catch (InstantiationException | IllegalAccessException e) {
			e.printStackTrace();
		}
	}
	
	public void add(String series, double x, double y) {
		if (dataset instanceof DefaultCategoryDataset)
			((DefaultCategoryDataset) dataset).addValue(y, series, x + "");
		else if (dataset instanceof XYSeriesCollection) {
			XYSeriesCollection dataset = (XYSeriesCollection) this.dataset;
			XYSeries xys = null;
			try {
				xys = dataset.getSeries(series);
			} catch (Exception e) {
				xys = new XYSeries(series);
				if (addZeros)
					xys.add(0.0, 0.0);
				dataset.addSeries(xys);
			}
			xys.add(x, y);
		}
//		validLabel=false;
		updateGraph();
		updateImage();
	}
	
	public void clear() {
		if (dataset instanceof DefaultCategoryDataset)
			((DefaultCategoryDataset) dataset).clear();
		else if (dataset instanceof XYSeriesCollection)
			((XYSeriesCollection) dataset).removeAllSeries();		
		markers.clear();
		//validLabel=false;
	}
	
	public void updateImage() {		
		if (alreadyUpdating /*|| validLabel*/)
			return;
		
		alreadyUpdating = true;
		
		if (dataset != null && graph != null) {
			ImageIcon icon;
			int width = getSize().width;
			if (width == 0)
				width = 400;
			int height = getSize().height;
			if (height == 0)
				height = 300;
			try {
				icon = new ImageIcon(graph.createBufferedImage(
						width,
						height));
			} catch (NullPointerException e) {
				icon = new ImageIcon();
			}
			label.setIcon(icon);
			label.setVisible(true);
			setPreferredSize(label.getPreferredSize());

			label.validate();
			//validLabel=true;
		}
		
		alreadyUpdating = false;
	}
	
	private boolean alreadyUpdating = false;
	
	private static boolean useScatterInsteadOfLine = false;
	private static boolean addZeros = true;
	
	@SuppressWarnings("deprecation")
	public void updateGraph() {
		if (alreadyUpdating)
			return;
		//validLabel = false;
		alreadyUpdating = true;
		
		Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
		Font font2 = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
		BasicStroke stroke = new BasicStroke(2.0f,                     // Line width
				BasicStroke.CAP_ROUND,     // End-cap style
				BasicStroke.JOIN_ROUND);   // Vertex join style
		
		float dash1[] = {10.0f};
		BasicStroke dotted = new BasicStroke(2.0f,                     // Line width
				BasicStroke.CAP_ROUND,     // End-cap style
				BasicStroke.JOIN_ROUND,   // Vertex join style
				10.0f, dash1, 0.0f);
		
		if (dataset instanceof DefaultCategoryDataset) {
			DefaultCategoryDataset dataset = (DefaultCategoryDataset) this.dataset;
			
			graph = ChartFactory.createLineChart(title, x, 
					y, dataset, PlotOrientation.VERTICAL, true, true, false);
			{
				CategoryPlot plot = (CategoryPlot) graph.getPlot();
				LineAndShapeRenderer renderer = (LineAndShapeRenderer) plot
						.getRenderer();
				
				graph.setAntiAlias(true);
				graph.setTextAntiAlias(RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
				
				if (pointsVisible) {
					renderer.setShapesVisible(true);
					renderer.setDrawOutlines(true);
					renderer.setUseFillPaint(true);
					renderer.setFillPaint(Color.white);
				}
				
				renderer.setStroke(stroke);
				
				double maxMarker = Double.MIN_VALUE, minMarker = Double.MAX_VALUE;
				
				for (Marker m : markers) {
					org.jfree.chart.plot.Marker marker;
					if (!m.isVertical) {
						marker = new ValueMarker(m.position);  // position is the value on the axis
					} else {
						marker = new CategoryMarker(m.position);
					}
					marker.setLabel(m.label);
					marker.setLabelFont(font2);
					marker.setStroke(dotted);
					marker.setLabelTextAnchor(TextAnchor.TOP_LEFT);
					marker.setLabelAnchor(RectangleAnchor.BOTTOM_LEFT);
//					marker.setLabelOffset(new RectangleInsets(10.0, 10.0, 3.0, 3.0));
					
					if (m.hiddenLabel != null && !m.hiddenLabel.equals("")) {
						Paint p = renderer.lookupSeriesPaint(dataset.getRowIndex(m.hiddenLabel));
						if (p != null)
							marker.setPaint(p);
					} else if (!m.label.equals("")) {
						Paint p = renderer.lookupSeriesPaint(dataset.getRowIndex(m.label));
						if (p != null)
							marker.setPaint(p);
					}
					
					if (!m.isVertical) {
						plot.addRangeMarker(marker);
						
						if (maxMarker < m.position)
							maxMarker = m.position;
						if (minMarker > m.position)
							minMarker = m.position;
					} else {
						plot.addDomainMarker((CategoryMarker)marker);
					}
				}
	
				NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
				rangeAxis.setTickLabelFont(font);
				
				if (!defaultRange) {
					double min = Double.MAX_VALUE, max = Double.MIN_VALUE, tmp;
					for (int i = 0; i < dataset.getColumnCount(); ++i)
						for (int j = 0; j < dataset.getRowCount(); ++j) {
							tmp = dataset.getValue(j, i).doubleValue();
							if (tmp < min)
								min = tmp;
							if (tmp > max)
								max = tmp;
						}
					
					if (min > minMarker)
						min = minMarker;
					if (max < maxMarker)
						max = maxMarker;
					
					if (min == Double.MAX_VALUE)
						min = 0;
					if (max == Double.MIN_VALUE)
						max = (int) rangeAxis.getRange().getUpperBound() + 1;
		
					if (exactMin != null)
						min = exactMin;
					else
						min = min - shownValsBelowMin;
					
					if (exactMax != null)
						max = exactMax;
					else
						max = max + shownValsAboveMax;
					
					if (min == max) {
						min = min - 1;
						max = max + 1;
					}
					
					rangeAxis.setRange(min, max);
				} else {
					double min = rangeAxis.getRange().getLowerBound();
					double max = rangeAxis.getRange().getUpperBound();
					
					if (min > minMarker)
						min = minMarker;
					if (max < maxMarker)
						max = maxMarker;
					
					rangeAxis.setRange(min, max);
				}
				
				CategoryAxis categoryAxis = plot.getDomainAxis();
				categoryAxis.setLowerMargin(0.02);
				categoryAxis.setUpperMargin(0.02);
				categoryAxis.setTickLabelFont(font);
	
				if (labelsVisible) {
					CategoryItemRenderer renderer2 = plot
							.getRenderer();
					CategoryItemLabelGenerator generator = new StandardCategoryItemLabelGenerator(
							"{2}", new DecimalFormat("0") {
		
								private static final long serialVersionUID = 1L;
		
								@Override
								public StringBuffer format(double number,
										StringBuffer result, FieldPosition fieldPosition) {
									result = new StringBuffer(getFormattedValue(number));
									return result;
								}
		
							});
					renderer2.setItemLabelGenerator(generator);
					renderer2.setItemLabelsVisible(true);
					renderer2.setItemLabelFont(font);
				}
			}
		} else if (dataset instanceof XYSeriesCollection) {
			
			XYSeriesCollection dataset = (XYSeriesCollection) this.dataset;
			if (!useScatterInsteadOfLine) {
				graph = ChartFactory.createXYLineChart(title, x, 
					y, dataset, PlotOrientation.VERTICAL, true, true, false);
			} else {
				graph = ChartFactory.createScatterPlot(title, x, y, dataset, PlotOrientation.VERTICAL, true, true, false);
			}
			
			{
				XYPlot plot = (XYPlot) graph.getPlot();
				XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot
						.getRenderer();
				
				graph.setAntiAlias(true);
				graph.setTextAntiAlias(RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
				
				if (pointsVisible) {
					renderer.setShapesVisible(true);
					renderer.setDrawOutlines(true);
					renderer.setUseFillPaint(true);
					renderer.setFillPaint(Color.white);
				}
				
				renderer.setStroke(stroke);
				
				double maxMarker = Double.MIN_VALUE, minMarker = Double.MAX_VALUE;
				
				for (Marker m : markers) {
					ValueMarker marker = new ValueMarker(m.position);  // position is the value on the axis
					marker.setLabel(m.label);
					marker.setLabelFont(font2);
					marker.setStroke(dotted);
					marker.setLabelTextAnchor(TextAnchor.TOP_LEFT);
					marker.setLabelAnchor(RectangleAnchor.BOTTOM_LEFT);
//					marker.setLabelOffset(new RectangleInsets(10.0, 10.0, 3.0, 3.0));
					
					if (!m.label.equals("") && dataset.getSeriesIndex(m.label) != -1) {
						Paint p = renderer.lookupSeriesPaint(dataset.getSeriesIndex(m.label));
						if (p != null)
							marker.setPaint(p);
					}
					
					if (!m.isVertical) {
						plot.addRangeMarker(marker);
						
						if (maxMarker < m.position)
							maxMarker = m.position;
						if (minMarker > m.position)
							minMarker = m.position;
					} else {
						plot.addDomainMarker(marker);
					}
				}
	
				ValueAxis rangeAxis = (ValueAxis) plot.getRangeAxis();
				rangeAxis.setTickLabelFont(font);
				
				if (!defaultRange) {
					double min = Double.MAX_VALUE, max = Double.MIN_VALUE, tmp;
					
					for (Object o : dataset.getSeries()) {
						XYSeries xys = (XYSeries) o;
						tmp = xys.getMaxY();
						if (tmp > max)
							max = tmp;
						tmp = xys.getMinY();
						if (tmp < min)
							min = tmp;
					}
					
					if (min > minMarker)
						min = minMarker;
					if (max < maxMarker)
						max = maxMarker;
					
					if (min == Double.MAX_VALUE)
						min = 0;
					if (max == Double.MIN_VALUE)
						max = (int) rangeAxis.getRange().getUpperBound() + 1;
		
					if (exactMin != null)
						min = exactMin;
					else
						min = min - shownValsBelowMin;
					
					if (exactMax != null)
						max = exactMax;
					else
						max = max + shownValsAboveMax;
					
					if (min == max) {
						min = min - 1;
						max = max + 1;
					}
					
					rangeAxis.setRange(min, max);
				} else {
					double min = rangeAxis.getRange().getLowerBound();
					double max = rangeAxis.getRange().getUpperBound();
					
					if (min > minMarker)
						min = minMarker;
					if (max < maxMarker)
						max = maxMarker;
					
					rangeAxis.setRange(min, max);
				}
				
				ValueAxis categoryAxis = plot.getDomainAxis();
				categoryAxis.setLowerMargin(0.02);
				categoryAxis.setUpperMargin(0.02);
				categoryAxis.setTickLabelFont(font);
			}
		} else {
			graph = null;
		}
		
		alreadyUpdating = false;
	}
	
	public String getFormattedValue(double number) {
		return (int)Math.round(number) + "";
	}
	
	public void save2png(String path, String fileName, int width, int height) throws IOException {
		File f = Paths.get(path, fileName).toFile();
		if (f.isDirectory())
			return;
		f.getParentFile().mkdirs();
		ChartUtilities.writeChartAsPNG(
				new FileOutputStream(f), graph, width, height);
	}
	
	public void save2png(String path, String fileName) throws IOException {
		this.save2png(path, fileName, imageWidth, imageHeight);
	}
	
	private int imageWidth = 1350;
	private int imageHeight = 700;
	
	public static GenericChart<XYSeriesCollection> createDemandLawGraph() throws NumberFormatException, IOException {
		GenericChart<XYSeriesCollection> graph = new GenericChart<XYSeriesCollection>("X", "U");
		graph.dataset = new XYSeriesCollection();
		graph.defaultRange = true;
		graph.labelsVisible = false;
		graph.pointsVisible = false;
		
		graph.title = "Demand Law";
//		graph.imageWidth = 2000;
//		graph.imageHeight = 1200;
		
		useScatterInsteadOfLine = true;
		addZeros = false;
		
		return graph;
	}
	
	public static GenericChart<XYSeriesCollection> createGraphFromData(List<Datum> data, double maximum, long minTimestamp, Marker... markers) throws NumberFormatException, IOException {
		GenericChart<XYSeriesCollection> graph = new GenericChart<XYSeriesCollection>("t", "Value");
		graph.dataset = new XYSeriesCollection();
		graph.defaultRange = true;
		graph.labelsVisible = false;
		graph.pointsVisible = false;
		
		graph.title = "Data";
//		graph.imageWidth = 2000;
//		graph.imageHeight = 1200;
		
//		useScatterInsteadOfLine = true;
		addZeros = false;
		
		if (data != null) {
			for (Datum d : data) {
				graph.add(d.resourceId + ":" + d.metric, (d.timestamp - minTimestamp)/1000, d.value > maximum ? maximum : d.value);
			}

			for (Marker m : markers)
				graph.addMarker(m.position, m.label);
			
			if (maximum < Double.MAX_VALUE)
				graph.addMarker(maximum, "Maximum Value");
		} else {
			graph.add("none", 0, 0);
		}
		
		graph.updateGraph();
		graph.updateImage();
		
		return graph;
	}
	
	public GenericChart<E> addDataAsVerticalMarkers(List<Datum> data, double minTimestamp) {
		if (data != null) {
			for (Datum d : data) {
				Marker m = new Marker((d.timestamp - minTimestamp)/1000, Double.toString(d.value));
				m.isVertical = true;
				m.hiddenLabel = Double.toString(d.value);
				markers.add(m);
			}
		}
		
		updateGraph();
		updateImage();
		
		return this;
	}
	
}
