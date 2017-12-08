# GanglionAndroidBluetooth

This project is a extension of https://github.com/abek42/OpenBCIBLE and thus a further extension of
https://github.com/googlesamples/android-BluetoothLeGatt configured for the OpenBCI Ganglion board (http://openbci.com/).

The additions are mostly parts of the existing Python code https://github.com/OpenBCI/OpenBCI_Python, which have been translated to java code.

The current features are connecting to a Ganglion Board, converting the data to int and then saving the data of all 4 channels to a .csv file along with the sampleID.

The data is also not yet scaled using the Scale factor to convert from counts to Volts: 

Scale Factor (Volts/count) = 1.2 Volts * 8388607.0 * 1.5 * 51.0 
from http://docs.openbci.com/Hardware/08-Ganglion_Data_Format#ganglion-data-format-interpreting-the-eeg-data

The Application has not been tested yet, which means it still needs to be verified that the parsing and decompression was translated and adjusted successfully.

I noticed, that there is some packet loss, which is around 0.5 to 1 percent. From what I understand this is normal.

For our purpose there is currently no need for the 18Bit compression, but the Impedance check and handling of ASCII characters functionality will both be added in the near future.

