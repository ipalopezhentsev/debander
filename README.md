Some filmscanners (I have Plustek OpticFilm 7600 and Pacific Image PrimeFilm XEs) have banding problems visible on some frames (usually on overexposed blue skies).
This utility fixes this.

It takes a 'light-frame' from non-compressed VueScan 16-bit TIFF image of scanner's frame _without_ any film - if you increase contrast of such frame in Photoshop, you'll see the bands clearly.

So the program computes properties of the bands and takes them out of a normal scanned non-compressed TIFF that you specify.

TODO:
1. improve input parameters passing
2. add detection of horizontal/vertical frames?
3. warn of not-16 bit inputs?
4. make exe?
5. make github official builds?
6. add picture to this readme pre/post
7. write article?