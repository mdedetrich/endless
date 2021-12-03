package endless.core.protocol

/** Generic binary decoder
  * @tparam A
  *   value
  */
trait Decoder[+A] {

  /** Decode binary array into value of type `A`
    * @param payload
    *   array of bytes
    * @return
    *   value
    */
  def decode(payload: Array[Byte]): A
}