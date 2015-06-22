package org.dia

import org.apache.spark.{SparkConf,SparkContext}
import org.jblas.DoubleMatrix
import breeze.linalg.{accumulate,sum, DenseMatrix}
import breeze.util.DoubleImplicits
import ucar.ma2
import ucar.nc2.dataset.NetcdfDataset
/**
 * Created by rahulsp on 6/17/15.
 */
object Main {

  /**
   * NetCDF variables to use
   * TODO:: Make the netcdf variables global - however this may need broadcasting
   */


  /** 
   * Variable names
    */
  val TotCldLiqH2O = "TotCldLiqH2O_A"
  val data = "data"

  /**
   * JBLAS implementation
   * The url is the base url where the netcdf file is located.
   * 1) Fetch the variable array from via the NetCDF api
   * 2) Download and convert the netcdf array to 1D array of doubles
   * 3) Reformat the array as a jblas Double Matrix, and reshape it with the original coordinates
   * 
   * TODO :: How to obtain the array dimensions from the netcdf api, 
   *         instead of hardcoding for reshape function
   * @param url
   * @param variable
   * @return
   */
  def getJblasNetCDFVars (url : String, variable : String) : DoubleMatrix = {
    val netcdfFile = NetcdfDataset.openDataset(url);
    val SearchVariable: ma2.Array = netcdfFile.findVariable(variable).read()

    val coordinateArray = SearchVariable.copyTo1DJavaArray().asInstanceOf[Array[Float]].map(p =>{
      var v = p.toDouble
      if(v == Double.NaN) v = 0
      v
    } )
    val matrix = new DoubleMatrix(coordinateArray).reshape(180, 360)
    matrix
  }



  /**
   * Breeze implementation
   * The url is the base url where the netcdf file is located.
   * 1) Fetch the variable array from via the NetCDF api
   * 2) Download and convert the netcdf array to 1D array of doubles
   * 3) Reformat the array as a jblas Double Matrix, and reshape it with the original coordinates
   *
   * TODO :: How to obtain the array dimensions from the netcdf api,
   *         instead of hardcoding for reshape function
   * @param url
   * @param variable
   * @return
   */
  def getBreezeNetCDFVars (url : String, variable : String) : DenseMatrix[Double] = {
    NetcdfDataset.setUseNaNs(false)
    val netcdfFile = NetcdfDataset.openDataset(url);
    val SearchVariable: ma2.Array = netcdfFile.findVariable(variable).read()

    val coordinateArray = SearchVariable.copyTo1DJavaArray()
      .asInstanceOf[Array[Float]]
      .map(p => {
      var v = p.toDouble
      v = if(v == -9999.0) 0.0 else v
      v
    })

    val matrix = new DenseMatrix(180, 360, coordinateArray, 0)
    matrix
  }

  /**
   * 
   * @param largeArray
   * @param blockSize
   * @return
   */
  def jblasreduceResolution(largeArray : DoubleMatrix, blockSize : Int) : DoubleMatrix =  {
    val numRows = largeArray.rows
    val numCols = largeArray.columns

    val reducedSize = numRows * numCols / (blockSize * blockSize)

    val reducedMatrix = DoubleMatrix.zeros(numRows / blockSize, numCols / blockSize)
    for(row <- 0 to reducedMatrix.rows - 1){
      for(col <- 0 to reducedMatrix.columns - 1){
        val block = largeArray.getRange(row * blockSize, ((row + 1) * blockSize) , col * blockSize,  ((col + 1) * blockSize))
        val average = block.mean
        reducedMatrix.put(row, col, average)
      }
    }

    reducedMatrix
  }

  def breezereduceResolution(largeArray : DenseMatrix[Double], blockSize : Int) : DenseMatrix[Double] = {
    val numRows = largeArray.rows
    val numCols = largeArray.cols

    val reducedSize = numRows * numCols / (blockSize * blockSize)
    val reducedMatrix = DenseMatrix.zeros[Double](numRows / blockSize, numCols / blockSize)

    for(row <- 0 to reducedMatrix.rows - 1){
      for(col <- 0 to reducedMatrix.cols - 1){
        val rowIndices = (row * blockSize) to (((row + 1)) * blockSize - 1)
        val colIndices = (col * blockSize) to ((col + 1) * blockSize - 1)
        val block = largeArray(rowIndices, colIndices)
        val totalsum = sum(block)
        val validCount = block.findAll(p => p != 0.0).size.toDouble
        val average = if(validCount > 0) totalsum / validCount else 0.0
        reducedMatrix(row to row, col to col) := average
        reducedMatrix
      }
    }

    reducedMatrix
  }

  def main(args : Array[String]) : Unit = {
    OpenDapURLGenerator.run()
    val conf = new SparkConf().setAppName("L").setMaster("local[4]")
    val sparkContext = new SparkContext(conf)
    val urlRDD = sparkContext.textFile("Links").repartition(4)

    /**
     * Uncomment this line in order to test on a normal scala array
     * val urlRDD = Source.fromFile("Links").mkString.split("\n")
     */

    val HighResolutionArray = urlRDD.map(url => getBreezeNetCDFVars(url, TotCldLiqH2O))

    val LowResolutionArray = HighResolutionArray.map(largeArray => breezereduceResolution(largeArray, 20))
    println(LowResolutionArray.count)

    LowResolutionArray.collect.map(p => {
      println(p)
    })
    //println("Hello World!")
  }
}

