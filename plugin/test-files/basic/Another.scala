package testfiles.basic

class Another:
  def process(data: List[Int]): List[Int] =
    data.map(_ + 1)
    
  def combine(a: Int, b: Int): Int =
    a + b