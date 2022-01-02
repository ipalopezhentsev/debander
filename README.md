Some filmscanners (I have Plustek OpticFilm 7600 and Pacific Image PrimeFilm XEs) have banding problems visible on some frames (usually on overexposed blue skies).
This utility tries to minimize this.

It takes a 'light-frame' from non-compressed VueScan TIFF image of scanner's frame _without_ any film - if you increase contrast of such frame in Photoshop, you'll see the bands clearly.

So the program computes properties of the bands and takes them out of a normal scanned non-compressed TIFF that you specify.

The solution is not 100% full, it decreases the bands perceptibly but not entirely.

TODO:
1. try subtracting not dividing, with adjustible global multiplier.
2. improve input parameters passing